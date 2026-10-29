#!/usr/bin/env bash
# Single-file installer; the embedded Python uses only the standard library.
set +x
set -Eeuo pipefail
umask 022

(( $# <= 1 )) || { printf 'Choose exactly one mode.\n' >&2; exit 2; }
case "${1:-}" in
  --help|-h)
    printf 'Usage: sudo bash %s [--upgrade] | bash %s --check\n' "$0" "$0"
    printf 'Ubuntu/Debian host tools only; no deployment, secrets or production volumes.\n'
    exit 0 ;;
  ''|--check|--upgrade) ;;
  *) printf 'Unknown argument. Use --help.\n' >&2; exit 2 ;;
esac

# A minimal Debian installation may not yet have Python. Bootstrap from signed
# distro sources only, never from an installer fetched from the Internet.
if ! command -v python3 >/dev/null 2>&1; then
  [[ ! -d /etc/pve && ! -e /usr/bin/pveversion ]] || { echo 'Proxmox VE host refused.' >&2; exit 1; }
  if [[ $(dpkg-query -W -f='${db:Status-Status}' pve-manager 2>/dev/null || true) == installed ]]; then
    echo 'Proxmox VE host refused.' >&2; exit 1
  fi
  [[ ${1:-} != --check ]] || { echo 'FAIL: Python 3 missing; read-only check cannot continue. No resources were changed.' >&2; exit 1; }
  (( EUID == 0 )) || { echo 'Run installation with sudo.' >&2; exit 1; }
  audit=$(dpkg --audit 2>&1) || { echo 'dpkg audit failed; manual package maintenance required.' >&2; exit 1; }
  [[ -z $audit ]] || { echo 'Incomplete dpkg state; manual package maintenance required before setup.' >&2; exit 1; }
  # /etc/os-release is root-managed; do not source arbitrary environment paths.
  ID='' VERSION_CODENAME=''
  # shellcheck disable=SC1091
  source /etc/os-release
  [[ $ID == ubuntu || $ID == debian ]] && [[ $VERSION_CODENAME =~ ^[a-z][a-z0-9-]*$ ]] || {
    echo 'Only Ubuntu/Debian with an official release codename are supported.' >&2; exit 1;
  }
  arch=$(dpkg --print-architecture)
  [[ $arch =~ ^[a-z0-9]+$ ]] || exit 1
  echo 'Python 3 is missing. Bootstrap installs python3, gnupg and ca-certificates from official HTTPS distro sources.'
  read -r -p 'Install bootstrap prerequisites? Type ja: ' answer </dev/tty
  [[ $answer == ja ]] || { echo 'Aborted.' >&2; exit 1; }
  tmp=$(mktemp -d)
  trap 'rm -rf -- "$tmp"' EXIT
  chmod 0755 "$tmp"
  mkdir "$tmp/lists" "$tmp/archives" "$tmp/empty-config"
  printf 'Dir::Etc::main "/dev/null";\nDir::Etc::parts "%s/empty-config";\nDir::Etc::netrc "/dev/null";\nDir::Etc::netrcparts "%s/empty-config";\n' "$tmp" "$tmp" >"$tmp/apt.conf"
  if [[ $ID == ubuntu ]]; then
    uri=https://ports.ubuntu.com/ubuntu-ports
    [[ $arch != amd64 && $arch != i386 ]] || uri=https://archive.ubuntu.com/ubuntu
    key=/usr/share/keyrings/ubuntu-archive-keyring.gpg
    components='main universe'
  else
    uri=https://deb.debian.org/debian
    key=/usr/share/keyrings/debian-archive-keyring.gpg
    components=main
  fi
  [[ -r $key ]] || { echo 'Trusted distribution archive keyring missing; manual repair required.' >&2; exit 1; }
  printf 'deb [arch=%s signed-by=%s] %s %s %s\n' "$arch" "$key" "$uri" "$VERSION_CODENAME" "$components" >"$tmp/sources.list"
  apt_options=(-o "Dir::Etc::sourcelist=$tmp/sources.list" -o Dir::Etc::sourceparts=-
    -o "Dir::State::lists=$tmp/lists" -o "Dir::Cache::archives=$tmp/archives"
    -o APT::Update::Error-Mode=any -o Acquire::AllowInsecureRepositories=false
    -o APT::Get::AllowUnauthenticated=false -o Acquire::https::Verify-Peer=true
    -o Acquire::https::Verify-Host=true -o Acquire::https::AllowRedirect=false)
  APT_CONFIG="$tmp/apt.conf" LC_ALL=C apt-get "${apt_options[@]}" update >"$tmp/apt.log" 2>&1 || { echo 'Signed HTTPS bootstrap index failed; usable trusted CA certificates are required.' >&2; exit 1; }
  APT_CONFIG="$tmp/apt.conf" LC_ALL=C apt-get "${apt_options[@]}" --simulate --no-remove --no-install-recommends install python3 gnupg ca-certificates >"$tmp/plan" 2>&1 || {
    echo 'Bootstrap simulation failed.' >&2; exit 1;
  }
  if grep -Eq '^(Remv |Inst [^ ]+ \[|(Inst|Conf) (docker-|containerd|runc))' "$tmp/plan"; then
    echo 'Bootstrap would replace installed packages; manual maintenance required.' >&2; exit 1
  fi
  audit=$(dpkg --audit 2>&1) || exit 1
  [[ -z $audit ]] || { echo 'dpkg state changed; manual maintenance required.' >&2; exit 1; }
  APT_CONFIG="$tmp/apt.conf" LC_ALL=C NEEDRESTART_MODE=l DEBIAN_FRONTEND=noninteractive apt-get "${apt_options[@]}" --yes --no-remove --no-install-recommends install python3 gnupg ca-certificates >"$tmp/apt.log" 2>&1 || {
    echo 'Bootstrap installation failed; no insecure fallback was attempted.' >&2; exit 1;
  }
fi

python3 -I -B - "$@" <<'PYTHON'
import collections
import contextlib
import datetime
import email.utils
import gzip
import hashlib
import json
import lzma
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request


OS_RELEASE = Path('/etc/os-release')
PVE_DIR = Path('/etc/pve')
PVE_BIN = Path('/usr/bin/pveversion')
REPO_DIR = Path('/opt/sporttracker')
SOURCES_DIR = Path('/etc/apt/sources.list.d')
SOURCES_LIST = Path('/etc/apt/sources.list')
KEYRINGS_DIR = Path('/etc/apt/keyrings')
DOCKER_STATE_DIRS = tuple(map(Path, ('/var/lib/docker', '/var/lib/containerd', '/etc/docker', '/etc/containerd')))
DOCKER_SOCKET = Path('/var/run/docker.sock')
REPO_URL = 'https://github.com/kruemelnerd/sporttracker.git'
GH_FLAGS = ('--repo', '--signer-workflow', '--source-ref', '--source-digest',
            '--cert-oidc-issuer', '--predicate-type', '--format', '--deny-self-hosted-runners')
DOCKER_PACKAGES = ('docker-ce', 'docker-ce-cli', 'containerd.io',
                   'docker-compose-plugin', 'docker-buildx-plugin', 'docker-ce-rootless-extras')
BASE_TOOLS = {'git': 'git', 'jq': 'jq', 'curl': 'curl', 'gpg': 'gnupg', 'gpgv': 'gpgv',
              'wget': 'wget', 'python3': 'python3', 'tar': 'tar', 'realpath': 'coreutils',
              'stat': 'coreutils', 'sha256sum': 'coreutils', 'df': 'coreutils',
              'awk': 'mawk', 'grep': 'grep', 'sed': 'sed', 'mktemp': 'coreutils'}
System = collections.namedtuple('System', 'distro version codename arch virt')


class SetupError(Exception):
    pass


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], capture_output=True, text=True,
                          timeout=kwargs.pop('timeout', 120), **kwargs)


def checked(args, label, **kwargs):
    result = run(args, **kwargs)
    if result.returncode:
        # Third-party stderr may contain credentials or environment information.
        raise SetupError(label + ' fehlgeschlagen; kein unsicherer Fallback.')
    return result.stdout


def installed_version(package):
    result = run(['dpkg-query', '-W', '-f=${db:Status-Status} ${Version}', package])
    fields = result.stdout.split()
    return fields[1] if not result.returncode and len(fields) == 2 and fields[0] == 'installed' else None


def assert_dpkg_clean():
    result = run(['dpkg', '--audit'])
    if result.returncode or result.stdout.strip() or result.stderr.strip():
        raise SetupError('Unvollständige dpkg-Paketkonfiguration: erst manuelle Wartung, keine automatische Reparatur.')


def detect_system():
    if PVE_DIR.exists() or PVE_BIN.exists():
        raise SetupError('Proxmox-VE-Host erkannt: Setup ausschließlich im Gast ausführen.')
    if installed_version('pve-manager') or installed_version('proxmox-ve'):
        raise SetupError('Proxmox-VE-Hostpakete erkannt: keine Installation auf dem Host.')
    values = {}
    for line in OS_RELEASE.read_text().splitlines():
        key, sep, value = line.partition('=')
        if sep and key in {'ID', 'VERSION_ID', 'VERSION_CODENAME'}:
            values[key] = value.strip().strip('"\'')
    distro, codename = values.get('ID'), values.get('VERSION_CODENAME', '')
    if distro not in {'ubuntu', 'debian'}:
        raise SetupError('Nur Ubuntu und Debian werden unterstützt, keine ungeprüften Derivate.')
    if not re.fullmatch(r'[a-z][a-z0-9-]*', codename):
        raise SetupError('Offizieller Release-Codename fehlt oder ist ungültig.')
    arch = checked(['dpkg', '--print-architecture'], 'Architekturerkennung').strip()
    if not re.fullmatch(r'[a-z0-9]+', arch):
        raise SetupError('Ungültige Paketarchitektur.')
    virt = 'unbekannt'
    if shutil.which('systemd-detect-virt'):
        result = run(['systemd-detect-virt'])
        if result.stdout.strip() and re.fullmatch(r'[a-z0-9-]+', result.stdout.strip()):
            virt = result.stdout.strip()
    print(f'System: {distro} {values.get("VERSION_ID", "unbekannt")} ({codename}), {arch}; Virtualisierung: {virt}')
    if virt == 'lxc':
        print('HINWEIS LXC: Nesting/Keyctl und Kernel-/AppArmor-/Seccomp-Kompatibilität am Host manuell prüfen. Keine Hoständerung durch Setup.')
    return System(distro, values.get('VERSION_ID', ''), codename, arch, virt)


def docker_command(*args):
    # Never inspect or change a daemon selected through a remote Docker context.
    return ['docker', '--host', 'unix:///var/run/docker.sock', *args]


def supports_flags(text, flags):
    return all(re.search(re.escape(flag) + r'(?![\w-])', text) for flag in flags)


def inventory():
    missing = set()
    for command, package in BASE_TOOLS.items():
        if not shutil.which(command):
            missing.add(package)
            print(f'FEHLT: {command}')
        else:
            print(f'VORHANDEN: {command}')
    if not installed_version('ca-certificates'):
        missing.add('ca-certificates')
    present = bool(shutil.which('docker'))
    if present:
        raw = checked(docker_command('info', '--format', '{{json .}}'),
                      'Lokaler Docker-Daemon (Berechtigung, Dienststatus oder LXC-Voraussetzungen prüfen)')
        try:
            info = json.loads(raw)
        except (ValueError, TypeError):
            raise SetupError('Docker meldet keine auswertbaren Laufzeitinformationen.') from None
        driver, cgroup = info.get('Driver'), str(info.get('CgroupVersion', ''))
        if driver not in {'overlay2', 'overlayfs', 'btrfs', 'zfs', 'fuse-overlayfs'} or cgroup not in {'1', '2'}:
            raise SetupError('Ungeprüfter Docker-Storage-Treiber oder cgroups-Modus; manuelle Kompatibilitätsprüfung erforderlich.')
        version = re.search(r'\d+(?:\.\d+)+', str(info.get('ServerVersion', '')))
        if not version:
            raise SetupError('Docker-Serverversion nicht feststellbar.')
        print(f'VORHANDEN: Docker Engine {version[0]}, Storage {driver}, cgroups v{cgroup}; lokaler Daemon erreichbar')
        compose = run(docker_command('compose', 'version'))
        help_up = run(docker_command('compose', 'up', '--help'))
        help_config = run(docker_command('compose', 'config', '--help'))
        if compose.returncode or help_up.returncode or help_config.returncode or not supports_flags(
                help_up.stdout, ('--wait', '--wait-timeout')) or not supports_flags(help_config.stdout, ('--quiet',)):
            missing.add('docker-compose-plugin')
        else:
            print('VORHANDEN: Docker Compose mit Readiness-/Konfigurationsprüfung')
        if run(docker_command('buildx', 'version')).returncode:
            missing.add('docker-buildx-plugin')
        else:
            print('VORHANDEN: Docker Buildx')
        if not shutil.which('containerd') or run(['containerd', '--version']).returncode:
            missing.add('containerd.io')
        else:
            print('VORHANDEN: containerd')
    else:
        if any(installed_version(package) for package in DOCKER_PACKAGES):
            raise SetupError('Vorhandene Docker-Pakete ohne funktionsfähige CLI: manuelle Reparatur erforderlich.')
        if DOCKER_SOCKET.exists() or any(path.is_symlink() or (path.exists() and (not path.is_dir() or any(path.iterdir())))
                                        for path in DOCKER_STATE_DIRS):
            raise SetupError('Vorhandener Docker-/containerd-Zustand ohne CLI: keine sichere Erstinstallation; manuelle Wartung erforderlich.')
        missing.update(DOCKER_PACKAGES[:5])
        print('FEHLT: Docker Engine/CLI, Compose, Buildx und containerd')
    gh = run(['gh', 'attestation', 'verify', '--help']) if shutil.which('gh') else None
    if gh is None or gh.returncode or not supports_flags(gh.stdout, GH_FLAGS) or 'json' not in gh.stdout.lower():
        missing.add('gh')
        print('FEHLT: GitHub CLI mit sämtlichen erforderlichen Attestation-Optionen')
    else:
        print('VORHANDEN: GitHub CLI mit allen Verifier-Optionen einschließlich Runner-Ablehnung/JSON')
    trivy = run(['trivy', '--version']) if shutil.which('trivy') else None
    if trivy is None or trivy.returncode or not re.search(r'\d+\.\d+', trivy.stdout):
        missing.add('trivy')
        print('FEHLT: ausführbares Trivy')
    else:
        print('VORHANDEN: Trivy (noch kein Image-Scan ausgeführt)')
    return sorted(missing), present


def repositories(system):
    # Trust anchors, not package version pins. See docs/proxmox-first-install.md.
    return [
        dict(name='docker', uri=f'https://download.docker.com/linux/{system.distro}',
             suite=system.codename, component='stable', key_url=f'https://download.docker.com/linux/{system.distro}/gpg',
             fingerprints={'9DC858229FC7DD38854AE2D88D81803C0EBFCD88'}, packages=DOCKER_PACKAGES[:5]),
        dict(name='github-cli', uri='https://cli.github.com/packages', suite='stable', component='main',
             key_url='https://cli.github.com/packages/githubcli-archive-keyring.gpg',
             fingerprints={'2C6106201985B60E6C7AC87323F3D4EA75716059', '7F38BBB59D064DBCB3D84D725612B36462313325'}, packages=('gh',)),
        dict(name='trivy', uri='https://aquasecurity.github.io/trivy-repo/deb', suite='generic', component='main',
             key_url='https://aquasecurity.github.io/trivy-repo/deb/public.key',
             fingerprints={'825AD9036F7C850E6A6FED4935B8ACA44FD9CA9F'}, packages=('trivy',)),
    ]


class HTTPSOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if parsed.scheme != 'https' or parsed.username or parsed.password:
            raise SetupError('Unsichere Download-Weiterleitung abgelehnt.')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_https(url, limit=32 * 1024 * 1024):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.username or parsed.password:
        raise SetupError('Nur HTTPS-Downloads ohne Zugangsdaten sind zulässig.')
    try:
        opener = urllib.request.build_opener(HTTPSOnly())
        with opener.open(url, timeout=30) as response:
            data = response.read(limit + 1)
        if len(data) > limit:
            raise SetupError('Repository-Metadaten überschreiten die sichere Größenbegrenzung.')
        return data
    except SetupError:
        raise
    except Exception:
        raise SetupError('HTTPS-Download fehlgeschlagen; Netzwerk, Zertifikat oder offizielle Release-/Architekturunterstützung prüfen.') from None


def gpg_result(args, data, **kwargs):
    result = subprocess.run(args, input=data, capture_output=True, timeout=60, **kwargs)
    if result.returncode:
        raise SetupError('GPG-Schlüssel oder Repository-Signatur ungültig; Vorgang abgebrochen.')
    return result.stdout


def verify_key(data, allowed):
    common = ['gpg', '--no-options', '--homedir', '/nonexistent', '--batch',
              '--no-default-keyring', '--keyring', '/dev/null', '--lock-never',
              '--trust-model', 'always', '--no-auto-check-trustdb']
    listing = gpg_result(common + ['--with-colons', '--import-options', 'show-only', '--import'], data).decode()
    primary, awaiting = set(), False
    for line in listing.splitlines():
        fields = line.split(':')
        if fields[0] in {'pub', 'sub'}:
            awaiting = fields[0] == 'pub'
        elif fields[0] == 'fpr' and awaiting:
            primary.add(fields[9])
            awaiting = False
    if not primary or not primary <= allowed:
        raise SetupError('Repository-Schlüssel hat einen unbekannten Primary-Key-Fingerprint.')
    if data.startswith(b'-----BEGIN PGP'):
        return gpg_result(common + ['--dearmor'], data)
    return data


def verify_release(key, signed_release):
    # Linux memfd keeps --check completely read-only, including GnuPG homes.
    fd = os.memfd_create('sporttracker-keyring', os.MFD_CLOEXEC)
    try:
        os.write(fd, key)
        os.lseek(fd, 0, os.SEEK_SET)
        body = gpg_result(['gpgv', '--homedir', '/nonexistent', '--keyring', f'/proc/self/fd/{fd}',
                           '--output', '-'], signed_release, pass_fds=(fd,))
    finally:
        os.close(fd)
    fields = parse_fields(body.decode())
    now = datetime.datetime.now(datetime.timezone.utc)
    for name, value in fields.items():
        if name in {'Date', 'Valid-Until'}:
            try:
                date = email.utils.parsedate_to_datetime(value)
                if date.tzinfo is None or (name == 'Date' and date > now + datetime.timedelta(days=1)) or (name == 'Valid-Until' and date < now):
                    raise ValueError()
            except (TypeError, ValueError):
                raise SetupError('Abgelaufene oder zeitlich ungültige signierte Repository-Metadaten.') from None
    return fields


def parse_fields(text):
    fields, current = {}, None
    for line in text.splitlines():
        if line[:1].isspace() and current:
            fields[current] += '\n' + line.strip()
        elif ':' in line:
            current, value = line.split(':', 1)
            if current in fields:
                raise SetupError('Mehrdeutige Repository-Metadaten oder Paketquelle.')
            fields[current] = value.strip()
    return fields


def probe_repositories(system):
    if not shutil.which('gpg') or not shutil.which('gpgv'):
        raise SetupError('GnuPG/gpgv fehlen; offizielle Repository-Unterstützung noch nicht kryptografisch prüfbar.')
    probe_distro(system)
    result = {}
    for repo in repositories(system):
        key = verify_key(fetch_https(repo['key_url'], 1024 * 1024), repo['fingerprints'])
        base = repo['uri'] + '/dists/' + repo['suite'] + '/'
        release = verify_release(key, fetch_https(base + 'InRelease', 4 * 1024 * 1024))
        if repo['suite'] not in {release.get('Suite'), release.get('Codename')}:
            raise SetupError('Signierte Suite/Release stimmt nicht mit der angeforderten Paketquelle überein.')
        indices = {}
        for line in release.get('SHA256', '').splitlines():
            parts = line.split()
            if len(parts) == 3:
                indices[parts[2]] = parts[:2]
        prefix = repo['component'] + '/binary-' + system.arch + '/Packages'
        path = next((prefix + suffix for suffix in ('.xz', '.gz', '') if prefix + suffix in indices), None)
        if not path:
            raise SetupError(f'{repo["name"]}: offizielle Paketquelle unterstützt diese Release-/Architekturkombination nicht.')
        data = fetch_https(base + path)
        digest, size = indices[path]
        if not size.isdigit() or len(data) != int(size) or hashlib.sha256(data).hexdigest() != digest:
            raise SetupError('Paketindex stimmt nicht mit den signierten SHA256-Metadaten überein.')
        if path.endswith('.xz'):
            stream = lzma.LZMAFile(io_bytes(data))
        elif path.endswith('.gz'):
            stream = gzip.GzipFile(fileobj=io_bytes(data))
        else:
            stream = io_bytes(data)
        with stream:
            plain = stream.read(128 * 1024 * 1024 + 1)
        if len(plain) > 128 * 1024 * 1024:
            raise SetupError('Entpackter Paketindex überschreitet das Speicherlimit.')
        available = set()
        for paragraph in plain.decode().split('\n\n'):
            fields = parse_fields(paragraph)
            if fields.get('Architecture') in {system.arch, 'all'} and fields.get('Version'):
                available.add(fields.get('Package'))
        if not set(repo['packages']) <= available:
            raise SetupError(f'{repo["name"]}: erforderliche Pakete für diese Architektur fehlen in der signierten Quelle.')
        result[repo['name']] = (repo, key)
        print(f'PASS: {repo["name"]}: Schlüssel, Signatur, Release/Architektur und Paketindex geprüft')
    return result


def io_bytes(data):
    import io
    return io.BytesIO(data)


def source_entries():
    paths = ([SOURCES_LIST] if SOURCES_LIST.exists() else [])
    if SOURCES_DIR.exists():
        paths += sorted(SOURCES_DIR.glob('*.list')) + sorted(SOURCES_DIR.glob('*.sources'))
    for path in paths:
        if path.is_symlink():
            raise SetupError('APT-Quelldatei ist ein Symlink; vor Änderungen manuell prüfen.')
        text = path.read_text()
        if path.suffix == '.sources':
            for paragraph in re.split(r'\n\s*\n', text):
                fields = {key.lower(): value for key, value in parse_fields(paragraph).items()}
                if fields.get('enabled', '').lower() == 'no':
                    continue
                for uri in fields.get('uris', '').split():
                    yield uri, fields.get('suites', '').split(), fields.get('components', '').split(), fields.get('signed-by', ''), fields
        else:
            for line in text.splitlines():
                match = re.match(r'^\s*deb\s+(?:\[([^\]]+)\]\s+)?(\S+)\s+(\S+)\s+([^#]+)', line)
                if not match:
                    continue
                options = {key.lower(): value for key, value in
                           (item.split('=', 1) if '=' in item else (item, '') for item in shlex.split(match[1] or ''))}
                yield match[2], [match[3]], match[4].split(), options.get('signed-by', ''), options


def existing_sources(system):
    found = {}
    catalog = repositories(system)
    for uri, suites, components, key_path, options in source_entries():
        parsed = urllib.parse.urlsplit(uri)
        repo = next((item for item in catalog if parsed.hostname == urllib.parse.urlsplit(item['uri']).hostname
                     or (item['name'] == 'trivy' and parsed.hostname == 'get.trivy.dev')), None)
        if repo is None:
            continue  # Unrelated sources are never used in our isolated APT transaction.
        expected_uris = {repo['uri']}
        if repo['name'] == 'trivy':
            expected_uris.add('https://get.trivy.dev/deb')
        if (uri.rstrip('/') not in expected_uris or suites != [repo['suite']] or components != [repo['component']]
                or parsed.username or parsed.password or not key_path.startswith('/') or '\n' in key_path
                or set(options) - {'types', 'uris', 'suites', 'components', 'architectures', 'arch', 'signed-by', 'enabled'}
                or ('types' in options and 'deb' not in options['types'].split())):
            raise SetupError('Vorhandene Hersteller-Paketquelle ist abweichend/unsicher; manuelle Klärung, keine Überschreibung.')
        archs = options.get('architectures', options.get('arch', system.arch)).replace(',', ' ').split()
        if system.arch not in archs or repo['name'] in found:
            raise SetupError('Doppelte oder für die Architektur ungeeignete Hersteller-Paketquelle; manuell bereinigen.')
        keyfile = Path(key_path)
        if keyfile.is_symlink() or not keyfile.is_file():
            raise SetupError('Bestehender Repository-Keyring fehlt oder ist ein Symlink.')
        key = verify_key(keyfile.read_bytes(), repo['fingerprints'])
        release = verify_release(key, fetch_https(uri.rstrip('/') + '/dists/' + repo['suite'] + '/InRelease', 4 * 1024 * 1024))
        if repo['suite'] not in {release.get('Suite'), release.get('Codename')}:
            raise SetupError('Bestehender Keyring verifiziert nicht die erwartete Repository-Suite; manuelle Prüfung erforderlich.')
        found[repo['name']] = True
        print(f'VORHANDEN: signierte {repo["name"]}-Paketquelle; bleibt unverändert')
    return found


def distro_repositories(system):
    if system.distro == 'ubuntu':
        uri = 'https://archive.ubuntu.com/ubuntu' if system.arch in {'amd64', 'i386'} else 'https://ports.ubuntu.com/ubuntu-ports'
        security = 'https://security.ubuntu.com/ubuntu' if system.arch in {'amd64', 'i386'} else uri
        key = '/usr/share/keyrings/ubuntu-archive-keyring.gpg'
    else:
        uri, security = 'https://deb.debian.org/debian', 'https://deb.debian.org/debian-security'
        key = '/usr/share/keyrings/debian-archive-keyring.gpg'
    if not Path(key).is_file():
        raise SetupError('Vertrauenswürdiger Distributions-Keyring fehlt; manuelle Reparatur erforderlich.')
    return [(url, suite, Path(key)) for url, suite in
            ((uri, system.codename), (uri, system.codename + '-updates'), (security, system.codename + '-security'))]


def distro_sources(system):
    components = 'main universe' if system.distro == 'ubuntu' else 'main'
    return ''.join(f'deb [arch={system.arch} signed-by={key}] {url} {suite} {components}\n'
                   for url, suite, key in distro_repositories(system))


def probe_distro(system):
    for uri, suite, keyfile in distro_repositories(system):
        release = verify_release(keyfile.read_bytes(), fetch_https(uri + '/dists/' + suite + '/InRelease', 4 * 1024 * 1024))
        if suite not in {release.get('Suite'), release.get('Codename')} or system.arch not in release.get('Architectures', '').split():
            raise SetupError('Offizielle Distributionsquelle unterstützt diese Release-/Architekturkombination nicht.')
    print('PASS: offizielle Distributionsquellen einschließlich Updates/Security signiert und für die Architektur verfügbar')


@contextlib.contextmanager
def apt_context(system, verified, packages=()):
    assert_dpkg_clean()
    with tempfile.TemporaryDirectory(prefix='sporttracker-apt-') as tmp:
        root = Path(tmp)
        root.chmod(0o755)  # Public metadata only; _apt must be able to read keyrings.
        (root / 'lists').mkdir()
        (root / 'archives').mkdir()
        (root / 'empty-config').mkdir()
        # APT_CONFIG loads before system fragments; command-line -o alone cannot
        # remove inherited hooks or hostname-specific insecure TLS overrides.
        config = root / 'apt.conf'
        config.write_text(f'Dir::Etc::main "/dev/null";\nDir::Etc::parts "{root / "empty-config"}";\n'
                          f'Dir::Etc::netrc "/dev/null";\nDir::Etc::netrcparts "{root / "empty-config"}";\n')
        environment = ['env', f'APT_CONFIG={config}', 'LC_ALL=C']
        sources = distro_sources(system)
        for repo, key in verified.values():
            keyfile = root / (repo['name'] + '.gpg')
            keyfile.write_bytes(key)
            keyfile.chmod(0o644)
            sources += f'deb [arch={system.arch} signed-by={keyfile}] {repo["uri"]} {repo["suite"]} {repo["component"]}\n'
        (root / 'sources.list').write_text(sources)
        # Isolate sources AND preferences: unrelated PPAs and priority overrides
        # must not supply a vendor package or weaken the selected stable channel.
        preferences = 'Package: gh\nPin: origin cli.github.com\nPin-Priority: 990\n\n'
        status = checked(['dpkg-query', '-W', '-f=${binary:Package}\t${Version}\t${db:Status-Status}\n'], 'Paket-Bestandsaufnahme')
        for line in status.splitlines():
            fields = line.split('\t')
            if len(fields) != 3 or fields[2] != 'installed':
                continue
            name, version, _ = fields
            if not re.fullmatch(r'[a-z0-9+.:_-]+', name) or not re.fullmatch(r'[a-zA-Z0-9.+:~_-]+', version):
                raise SetupError('Ungültiger Paketstatus; keine APT-Transaktion möglich.')
            bare = name.split(':')[0]
            if bare not in packages or bare in DOCKER_PACKAGES:
                preferences += f'Package: {name}\nPin: version {version}\nPin-Priority: 1001\n\n'
        (root / 'preferences').write_text(preferences)
        options = ['-o', f'Dir::Etc::sourcelist={root / "sources.list"}', '-o', 'Dir::Etc::sourceparts=-',
                   '-o', f'Dir::Etc::preferences={root / "preferences"}', '-o', 'Dir::Etc::preferencesparts=-',
                   '-o', f'Dir::State::lists={root / "lists"}', '-o', f'Dir::Cache::archives={root / "archives"}',
                   '-o', 'APT::Update::Error-Mode=any', '-o', 'Acquire::AllowInsecureRepositories=false',
                   '-o', 'Acquire::AllowDowngradeToInsecureRepositories=false', '-o', 'APT::Get::AllowUnauthenticated=false',
                   '-o', 'Acquire::https::Verify-Peer=true', '-o', 'Acquire::https::Verify-Host=true',
                   '-o', 'Acquire::https::AllowRedirect=false', '-o', 'Acquire::Check-Valid-Until=true']
        checked([*environment, 'apt-get', *options, 'update'], 'APT-Signatur-/Indexprüfung', timeout=600)
        yield [*environment, 'apt-get', *options], [*environment, 'apt-cache', *options]


def guard_docker_upgrade(apt_cache, present):
    if not present:
        return
    running = checked(docker_command('ps', '--quiet'), 'Lesende Prüfung laufender Container').strip()
    for package in DOCKER_PACKAGES:
        old = installed_version(package)
        if old:
            policy = checked([*apt_cache, 'policy', package], 'Docker-Paketkandidat')
            match = re.search(r'^\s*Candidate:\s+(\S+)', policy, re.M)
            if not match or match[1] == '(none)' or match[1] != old:
                state = 'laufende Container vorhanden' if running else 'keine laufenden Container gemeldet'
                raise SetupError(f'Docker-/containerd-Upgrade erfordert manuelle Wartung ({state}); Paketskripte könnten den Daemon neu starten.')


def install_packages(apt_get, packages, docker_present):
    if not packages:
        return
    assert_dpkg_clean()
    args = ['--no-remove', '--no-install-recommends', 'install', *packages]
    plan = checked([*apt_get, '--simulate', *args], 'APT-Simulation', timeout=600)
    if re.search(r'^Remv\s', plan, re.M):
        raise SetupError('APT würde Pakete entfernen; Migration nur nach manueller Freigabe.')
    requested = set(packages)
    for line in plan.splitlines():
        match = re.match(r'(Inst|Conf)\s+(\S+)(?:\s+\[([^]]+)\])?', line)
        if not match:
            continue
        package = match[2].split(':')[0]
        if package in DOCKER_PACKAGES and (installed_version(package) or
                (docker_present and package not in {'docker-compose-plugin', 'docker-buildx-plugin'})):
            raise SetupError('APT würde vorhandene Docker-Komponenten ändern; manuelle Wartung erforderlich.')
        if match[3] and package not in requested:
            raise SetupError('APT würde nicht angeforderte vorhandene Abhängigkeiten ersetzen; manuelle Wartung erforderlich.')
    assert_dpkg_clean()
    checked([*apt_get, '--yes', *args], 'APT-Paketinstallation', timeout=1800,
            env={**os.environ, 'DEBIAN_FRONTEND': 'noninteractive', 'NEEDRESTART_MODE': 'l'})
    assert_dpkg_clean()


def secure_directory(path):
    if path.is_symlink() or (path.exists() and (not path.is_dir() or path.stat().st_uid != 0 or path.stat().st_mode & 0o022)):
        raise SetupError('Unsicheres Zielverzeichnis; keine Datei überschrieben.')
    path.mkdir(mode=0o755, parents=True, exist_ok=True)


def add_sources(system, verified, existing, packages):
    for repo, key in verified.values():
        if repo['name'] in existing or not set(repo['packages']) & set(packages):
            continue
        secure_directory(KEYRINGS_DIR)
        secure_directory(SOURCES_DIR)
        keyfile = KEYRINGS_DIR / ('sporttracker-' + repo['name'] + '.gpg')
        sourcefile = SOURCES_DIR / ('sporttracker-' + repo['name'] + '.sources')
        text = (f'Types: deb\nURIs: {repo["uri"]}\nSuites: {repo["suite"]}\nComponents: {repo["component"]}\n'
                f'Architectures: {system.arch}\nSigned-By: {keyfile}\n')
        if keyfile.is_symlink() or sourcefile.exists() or sourcefile.is_symlink():
            raise SetupError('Zieldatei für Herstellerquelle bereits belegt; keine Überschreibung.')
        if keyfile.exists():
            if keyfile.read_bytes() != key:
                raise SetupError('Abweichender vorhandener Hersteller-Keyring; manuelle Prüfung erforderlich.')
        else:
            with keyfile.open('xb') as handle:
                handle.write(key)
            keyfile.chmod(0o644)
        with sourcefile.open('x') as handle:
            handle.write(text)
        sourcefile.chmod(0o644)


def check_conflicts(docker_present):
    conflicts = [package for package in ('docker.io', 'docker-desktop', 'podman-docker', 'docker-compose',
                 'docker-compose-v2', 'docker-buildx', 'containerd', 'runc') if installed_version(package)]
    if shutil.which('snap') and run(['snap', 'list', 'docker']).returncode == 0:
        conflicts.append('Docker-Snap')
    if conflicts:
        raise SetupError('Konfliktvarianten erkannt: ' + ', '.join(conflicts) + '. Keine Deinstallation; Migration separat prüfen und bestätigen.')
    if shutil.which('podman'):
        print('WARNUNG: Podman vorhanden; keine automatische Migration oder Deinstallation.')


def start_new_docker():
    if shutil.which('systemctl') and Path('/run/systemd/system').is_dir():
        checked(['systemctl', 'start', 'docker'], 'Start des neu installierten Docker-Dienstes')
    else:
        raise SetupError('Docker installiert, aber kein unterstütztes laufendes Init-System; Dienst manuell starten und --check wiederholen.')


def prepare_repository(check_only):
    if REPO_DIR.is_symlink():
        raise SetupError('/opt/sporttracker ist ein Symlink; keine automatische Änderung.')
    if REPO_DIR.exists():
        if not (REPO_DIR / '.git').exists():
            raise SetupError('/opt/sporttracker existiert, ist aber kein erkennbares Git-Repository; bleibt unverändert.')
        # Even `git status` can execute repository-defined clean/process filters.
        git = ['git', '--no-optional-locks', '-C', REPO_DIR]
        url = checked([*git, 'config', '--local', '--no-includes', '--get', 'remote.origin.url'],
                      'Repository-Identitätsprüfung', env=git_environment()).strip()
        if url not in {REPO_URL, 'git@github.com:kruemelnerd/sporttracker.git'}:
            raise SetupError('Vorhandenes Repository hat eine andere Herkunft; keine automatische Änderung.')
        print('VORHANDEN: Repository; Dateien bleiben unverändert, Status/Updates bitte manuell prüfen')
        return True
    if check_only:
        print('FEHLT: /opt/sporttracker; --check klont nichts')
        return False
    checked(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'protocol.allow=never', '-c', 'protocol.https.allow=always',
             '-c', 'http.sslVerify=true', '-c', 'http.followRedirects=false', 'clone', '--template=', '--origin', 'origin',
             '--branch', 'main', '--single-branch', REPO_URL, REPO_DIR],
            'Öffentlicher HTTPS-Clone', timeout=600, env=git_environment())
    print('Repository unter /opt/sporttracker bereitgestellt; kein fremdes Skript als Root ausgeführt')
    return True


def git_environment():
    env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_SYSTEM='/dev/null',
               GIT_CONFIG_COUNT='0', GIT_TERMINAL_PROMPT='0', GIT_ASKPASS='/bin/false')
    return env


def resource_warnings():
    mem = {}
    for line in Path('/proc/meminfo').read_text().splitlines():
        key, _, value = line.partition(':')
        if key in {'MemTotal', 'SwapTotal'}:
            mem[key] = int(value.split()[0]) * 1024
    ram = mem.get('MemTotal', 0)
    limit = Path('/sys/fs/cgroup/memory.max')
    if limit.exists() and limit.read_text().strip().isdigit():
        ram = min(ram, int(limit.read_text().strip()))
    free = shutil.disk_usage('/').free
    print(f'Ressourcen: RAM ca. {ram // 1024**2} MiB; Swap laut /proc ca. {mem.get("SwapTotal", 0) // 1024**2} MiB; / frei ca. {free // 1024**3} GiB')
    if free < 5 * 1024**3:
        raise SetupError('Weniger als 5 GiB frei: kein verlässlicher Platz für Pakete/Images/Scanner; manuell Speicher bereitstellen.')
    if ram < 4 * 1024**3 or free < 20 * 1024**3:
        print('WARNUNG: Kleines System; 2 GiB RAM + 2 GiB Swap sind kein automatischer Ausschluss. Docker/App/DB und Trivy brauchen zusätzliche Reserven; OOM-/Scanfehler niemals überspringen.')


def confirm():
    try:
        answer = input('Änderungen wie beschrieben durchführen? Bitte exakt ja eingeben: ')
    except EOFError:
        raise SetupError('Keine interaktive Bestätigung; Abgebrochen.') from None
    if answer != 'ja':
        raise SetupError('Abgebrochen; keine Installation durchgeführt.')


def main(argv):
    if len(argv) > 1 or (argv and argv[0] not in {'--check', '--upgrade', '--help', '-h'}):
        raise SetupError('Ungültige Parameter; genau einen Modus wählen: --check oder --upgrade.')
    mode = argv[0] if argv else 'install'
    if mode in {'--help', '-h'}:
        print('sudo bash install-host-tools.sh [--upgrade] | bash install-host-tools.sh --check')
        return 0
    system = detect_system()
    check_only = mode == '--check'
    if not check_only and os.geteuid() != 0:
        raise SetupError('Installation mit sudo ausführen; --check benötigt kein Root.')
    resource_warnings()
    missing, docker_present = inventory()
    check_conflicts(docker_present)
    print('Setup ist kein Deployment und kein Release-Nachweis. Noch kein Release verifiziert und kein Image-Scan ausgeführt.')
    if system.arch != 'amd64':
        print('WARNUNG: Sporttracker veröffentlicht derzeit nur linux/amd64-Images; Werkzeuginstallation ist keine Deployment-Freigabe für diese Architektur.')
    if check_only:
        existing_sources(system)
        probe_repositories(system)
        repo_ok = prepare_repository(True) if shutil.which('git') else False
        print('Check beendet. No resources were changed. Kein Release verifiziert.')
        return int(bool(missing) or not repo_ok or system.arch != 'amd64')
    packages = missing
    if mode == '--upgrade':
        packages = sorted(set(missing) | {package for package in [*BASE_TOOLS.values(), 'ca-certificates', 'gh', 'trivy', *DOCKER_PACKAGES]
                                          if installed_version(package)})
        packages = [package for package in packages if package not in DOCKER_PACKAGES or not installed_version(package)]
    print('Geplante Pakete: ' + (', '.join(packages) if packages else 'keine; funktionierende Werkzeuge bleiben unverändert'))
    print('Repository: /opt/sporttracker bei Fehlen klonen; bestehende Dateien/Änderungen bleiben erhalten.')
    print('Docker verwaltet selbst Firewall-/Netzwerkregeln; veröffentlichte Ports können UFW umgehen. Keine SSH-/UFW-/LXC-Änderungen durch Setup.')
    confirm()
    # gpg/gpgv/CA can be missing even when Python is preinstalled.
    bootstrap = [package for package in ('gnupg', 'gpgv', 'ca-certificates')
                 if package in missing and not installed_version(package)]
    if bootstrap:
        with apt_context(system, {}, bootstrap) as (apt_get, _):
            install_packages(apt_get, bootstrap, docker_present)
    verified = probe_repositories(system)
    existing = existing_sources(system)
    if packages or mode == '--upgrade':
        with apt_context(system, verified, packages) as (apt_get, apt_cache):
            if mode == '--upgrade':
                # Inspect available vendor candidates without our protective pins.
                guard_docker_upgrade([*apt_cache, '-o', 'Dir::Etc::preferences=/dev/null'], docker_present)
            install_packages(apt_get, packages, docker_present)
        add_sources(system, verified, existing, packages)
    if not docker_present:
        start_new_docker()
    remaining, _ = inventory()
    if remaining:
        raise SetupError('Werkzeuge nach Installation noch nicht funktionsfähig: ' + ', '.join(remaining))
    prepare_repository(False)
    print('PASS: Host-Werkzeuge eingerichtet. Kein Deployment, kein Release verifiziert, keine Produktionsvolumes/Secrets erstellt.')
    return 0


if __name__ == '__main__':
    try:
        # Python source arrives through stdin; confirmations must use the terminal.
        if not any(arg in {'--check', '--help', '-h'} for arg in sys.argv[1:]):
            try:
                sys.stdin = open('/dev/tty')
            except OSError:
                pass  # Host checks still run; confirm() fails closed on EOF.
        sys.exit(main(sys.argv[1:]))
    except SetupError as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, subprocess.SubprocessError, KeyboardInterrupt):
        print('FAIL: Systemprüfung/Installation abgebrochen; keine Zugangsdaten oder Shell-Traces ausgegeben.', file=sys.stderr)
        sys.exit(1)
PYTHON
