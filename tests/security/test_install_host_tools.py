"""Installer contract tests: no real APT, Docker or repository mutation."""
import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/install-host-tools.sh"


class LauncherTests(unittest.TestCase):
    def test_help_does_not_need_python_or_root(self):
        with tempfile.TemporaryDirectory() as empty:
            result = subprocess.run(['/bin/bash', str(SCRIPT), '--help'], env={'PATH': empty}, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0)
            self.assertIn('--check', result.stdout)
            self.assertFalse(list(Path(empty).iterdir()))

    def test_combined_modes_are_rejected_even_with_help(self):
        result = subprocess.run(['/bin/bash', str(SCRIPT), '--help', '--upgrade'], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)

    def test_check_missing_python_does_not_bootstrap(self):
        with tempfile.TemporaryDirectory() as empty:
            result = subprocess.run(['/bin/bash', str(SCRIPT), '--check'], env={'PATH': empty}, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('No resources were changed', result.stderr)
            self.assertFalse(list(Path(empty).iterdir()))

    def test_untrusted_python_imports_cannot_execute_or_write_before_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            marker = root / 'untrusted-import-executed'
            (root / 'json.py').write_text(f'from pathlib import Path\nPath({str(marker)!r}).touch()\nraise SystemExit(0)\n')
            binary = root / 'bin'
            binary.mkdir()
            query = binary / 'dpkg-query'
            query.write_text('#!/bin/bash\necho "installed 1.0"\n')
            query.chmod(0o755)
            result = subprocess.run(['/bin/bash', str(SCRIPT), '--check'], cwd=root,
                                    env={**os.environ, 'PYTHONPATH': str(root), 'PATH': str(binary) + ':/usr/bin:/bin'},
                                    capture_output=True, text=True, timeout=15)
            self.assertFalse(marker.exists(), 'Python imported code from the working directory before confirmation')
            self.assertFalse((root / '__pycache__').exists(), '--check created bytecode files')
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Proxmox', result.stderr)


def load_installer():
    if not SCRIPT.exists():
        raise AssertionError("Single-file host installer has not been implemented")
    code = SCRIPT.read_text().split("<<'PYTHON'\n", 1)[1].rsplit("\nPYTHON", 1)[0]
    module = types.ModuleType("host_setup")
    exec(compile(code, str(SCRIPT), "exec"), module.__dict__)
    return module


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.app = load_installer()
        self.tmp = tempfile.TemporaryDirectory(prefix="host-setup-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.os_release = self.root / "os-release"
        self.os_release.write_text('ID=ubuntu\nVERSION_ID="26.04"\nVERSION_CODENAME=resolute\n')
        for name, path in {
            "OS_RELEASE": self.os_release,
            "PVE_DIR": self.root / "pve",
            "PVE_BIN": self.root / "pveversion",
            "REPO_DIR": self.root / "sporttracker",
            "SOURCES_DIR": self.root / "sources.list.d",
            "SOURCES_LIST": self.root / "sources.list",
            "KEYRINGS_DIR": self.root / "keyrings",
        }.items():
            setattr(self.app, name, path)
        self.calls = []
        self.installed = {'ca-certificates': '1.0'}
        self.missing = {'snap'}
        self.virt = "lxc"
        self.apt_error = False
        self.audit = ''
        self.apt_plan = ""
        self.running = ""
        self.candidate = "2.0"
        self.app.DOCKER_STATE_DIRS = (self.root / 'docker-data', self.root / 'containerd-data')
        self.app.DOCKER_SOCKET = self.root / 'docker.sock'
        self.app.SYSTEMD_RUNTIME = self.root / 'systemd-runtime'
        self.app.SNAP_DOCKER_CURRENT = self.root / 'snap-docker'
        self.app.SNAP_ARCHIVES = self.root / 'snap-archives'
        self.service_active = False
        self.gh_help = " ".join(self.app.GH_FLAGS) + " json"
        self.compose_help = '--wait --wait-timeout --quiet'
        self.patches = [
            patch.object(self.app, "run", side_effect=self.fake_run),
            patch.object(self.app.shutil, "which", side_effect=lambda name: None if name in self.missing else "/stub/" + name),
            patch.object(self.app.os, "geteuid", return_value=0),
        ]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)
        quiet = contextlib.redirect_stdout(io.StringIO())
        quiet.__enter__()
        self.addCleanup(quiet.__exit__, None, None, None)

    def fake_run(self, args, **kwargs):
        args = [str(arg) for arg in args]
        if args[0] == 'env':
            index = 1
            while '=' in args[index]:
                index += 1
            args = args[index:]
        self.calls.append(args)
        name = Path(args[0]).name
        out, status = "", 0
        if name == "dpkg":
            out = self.audit if '--audit' in args else 'amd64\n'
        elif name == "dpkg-query":
            if args[-1] in self.installed:
                out = "installed " + self.installed[args[-1]]
            elif "-W" in args and args[-1].startswith("-f="):
                out = "".join(f"{key}\t{value}\tinstalled\n" for key, value in self.installed.items())
            else:
                status = 1
        elif name == "systemd-detect-virt":
            out = self.virt + "\n"
            status = int(self.virt == "none")
        elif name == "docker":
            if "info" in args:
                out = '{"ServerVersion":"29.9.2","Driver":"overlayfs","CgroupVersion":"2"}'
            elif "ps" in args:
                out = self.running
            elif "--help" in args:
                out = self.compose_help
            elif "version" in args:
                out = "5.6.1"
            else:
                self.fail("Unexpected Docker mutation: " + repr(args))
        elif name == "gh":
            out = self.gh_help if "--help" in args else "gh version 2.99.0"
        elif name == "trivy":
            out = "Version: 0.99.0"
        elif name == "containerd":
            out = "containerd 2.0.0"
        elif name == "apt-get":
            status = int(self.apt_error)
            if "--simulate" in args:
                out = self.apt_plan
            elif "install" in args and not status:
                for package in args[args.index('install') + 1:]:
                    self.installed[package] = '2.0'
                    self.missing.discard(package)
                    for command, owner in self.app.BASE_TOOLS.items():
                        if owner == package:
                            self.missing.discard(command)
                    if package == 'docker-ce':
                        self.missing.discard('docker')
        elif name == "apt-cache":
            out = "  Candidate: " + self.candidate + "\n"
        elif name == "git":
            if "clone" in args:
                self.app.REPO_DIR.mkdir()
                (self.app.REPO_DIR / ".git").mkdir()
            elif "--get" in args:
                out = "https://github.com/kruemelnerd/sporttracker.git\n"
            elif "--is-inside-work-tree" in args:
                out = "true\n"
            elif "status" in args:
                out = " M user-file\n"
            else:
                self.fail("Unexpected Git operation: " + repr(args))
        elif name == 'systemctl' and 'show' in args:
            out = 'loaded\n'
        elif name == 'systemctl' and 'is-active' in args:
            status = 0 if self.service_active else 3
        elif name in {"systemctl", "service"}:
            self.fail("Unexpected service operation: " + repr(args))
        return subprocess.CompletedProcess(args, status, out, "sensitive-value" if status else "")

    def invoke(self, argv, answer="ja"):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch("builtins.input", return_value=answer):
            result = self.app.main(argv)
        return result, output.getvalue()

    def test_ubuntu_2604_and_lxc_detected_without_version_allowlist(self):
        system = self.app.detect_system()
        self.assertEqual((system.distro, system.codename, system.arch, system.virt), ("ubuntu", "resolute", "amd64", "lxc"))

    def test_debian_stable_detected(self):
        self.os_release.write_text("ID=debian\nVERSION_ID=13\nVERSION_CODENAME=trixie\n")
        self.assertEqual(self.app.detect_system().codename, "trixie")

    def test_ubuntu_2404_vm_and_bare_metal_use_the_same_checks(self):
        self.os_release.write_text('ID=ubuntu\nVERSION_ID=24.04\nVERSION_CODENAME=noble\n')
        for virt in ('kvm', 'none'):
            self.virt = virt
            system = self.app.detect_system()
            self.assertEqual((system.codename, system.virt), ('noble', virt))

    def test_proxmox_host_refused_before_any_download(self):
        self.app.PVE_DIR.mkdir()
        with self.assertRaisesRegex(self.app.SetupError, "Proxmox"):
            self.app.main([])
        self.assertFalse(self.calls)

    def test_pve_package_refused_but_pve_kernel_is_not_a_host_test(self):
        self.installed["pve-manager"] = "9.0"
        with self.assertRaisesRegex(self.app.SetupError, "Proxmox"):
            self.app.detect_system()

    def test_unsupported_distribution_rejected(self):
        self.os_release.write_text("ID=arch\nVERSION_ID=2026\n")
        with self.assertRaisesRegex(self.app.SetupError, "Ubuntu|Debian"):
            self.app.detect_system()

    def test_existing_docker_and_compose_major_five_are_functional(self):
        missing, docker = self.app.inventory()
        self.assertTrue(docker)
        self.assertNotIn("docker-ce", missing)
        self.assertNotIn("docker-compose-plugin", missing)
        self.assertTrue(any("info" in call for call in self.calls))

    def test_all_release_verifier_flags_are_required(self):
        for flag in self.app.GH_FLAGS:
            with self.subTest(flag=flag):
                self.gh_help = " ".join(value for value in self.app.GH_FLAGS if value != flag) + " json"
                self.assertIn("gh", self.app.inventory()[0])

    def test_similarly_named_flags_do_not_satisfy_required_runtime_features(self):
        self.gh_help = self.gh_help.replace('--source-ref', '--source-ref-extra')
        self.compose_help = '--wait-timeout --quiet'
        missing, _ = self.app.inventory()
        self.assertIn('gh', missing)
        self.assertIn('docker-compose-plugin', missing)

    def test_missing_base_dependency_is_planned(self):
        self.missing.add("jq")
        self.assertIn("jq", self.app.inventory()[0])

    def test_check_mode_never_creates_files_or_invokes_apt_git_or_service(self):
        before = sorted(str(path) for path in self.root.rglob("*"))
        with patch.object(self.app, "probe_repositories", return_value={}):
            code, output = self.invoke(["--check"])
        self.assertNotEqual(code, 0)  # missing repository is not a complete setup
        self.assertIn("kein Release", output)
        self.assertEqual(sorted(str(path) for path in self.root.rglob("*")), before)
        self.assertFalse(any(Path(call[0]).name in {"apt-get", "git", "systemctl", "service"} for call in self.calls))

    def test_declined_confirmation_causes_no_write(self):
        self.missing.add("jq")
        before = sorted(self.root.rglob("*"))
        with self.assertRaisesRegex(self.app.SetupError, "Abgebrochen"):
            self.invoke([], answer="nein")
        self.assertEqual(sorted(self.root.rglob("*")), before)

    def test_upgrade_refuses_docker_replacement_even_without_containers(self):
        self.installed["docker-ce"] = "1.0"
        with self.assertRaisesRegex(self.app.SetupError, "Wartung"):
            self.app.guard_docker_upgrade(["apt-cache"], True)
        self.assertTrue(any("ps" in call for call in self.calls))
        self.assertFalse(any("install" in call for call in self.calls))

    def test_upgrade_refuses_with_running_containers_and_never_stops_them(self):
        self.installed['docker-ce'] = '1.0'
        self.running = 'container-id-must-not-be-logged'
        with self.assertRaisesRegex(self.app.SetupError, 'laufende Container') as error:
            self.app.guard_docker_upgrade(['apt-cache'], True)
        self.assertNotIn(self.running, str(error.exception))
        self.assertFalse(any('stop' in call or 'restart' in call for call in self.calls))

    def test_apt_transaction_refuses_indirect_docker_upgrade(self):
        self.installed["docker-ce"] = "1.0"
        self.apt_plan = "Inst docker-ce [1.0] (2.0 Docker:stable [amd64])\n"
        with self.assertRaisesRegex(self.app.SetupError, "Docker|Wartung"):
            self.app.install_packages(["apt-get"], ["gh"], True)
        self.assertFalse(any("install" in call and "--simulate" not in call for call in self.calls))

    def test_apt_error_is_fatal_without_echoing_stderr_secrets(self):
        self.apt_error = True
        with self.assertRaises(self.app.SetupError) as error:
            self.app.install_packages(["apt-get"], ["jq"], True)
        self.assertNotIn("sensitive-value", str(error.exception))

    def test_pending_dpkg_configuration_blocks_before_any_apt_transaction(self):
        self.audit = 'The following packages are only half configured: docker-ce\n'
        with self.assertRaisesRegex(self.app.SetupError, 'dpkg|Paketkonfiguration'):
            self.app.install_packages(['apt-get'], ['gh'], True)
        self.assertFalse(any('install' in call for call in self.calls))

    def test_configuration_only_plan_cannot_restart_existing_docker(self):
        self.installed['docker-ce'] = '29.9.2'
        self.apt_plan = 'Conf docker-ce (29.9.2 Docker:stable [amd64])\n'
        with self.assertRaisesRegex(self.app.SetupError, 'Docker|Wartung'):
            self.app.install_packages(['apt-get'], ['gh'], True)
        self.assertFalse(any('install' in call and '--simulate' not in call for call in self.calls))

    def test_old_docker_state_is_not_started_as_a_fresh_install(self):
        self.missing.add('docker')
        old = self.app.DOCKER_STATE_DIRS[0]
        old.mkdir()
        (old / 'containers').mkdir()
        with self.assertRaisesRegex(self.app.SetupError, 'Docker|Wartung'):
            self.app.inventory()
        self.assertFalse(any('install' in call for call in self.calls))

    def test_inactive_docker_is_not_contacted_through_an_activating_socket(self):
        self.app.SYSTEMD_RUNTIME.mkdir()
        with self.assertRaisesRegex(self.app.SetupError, 'Socket|Dienst'):
            self.app.inventory()
        self.assertFalse(any('info' in call for call in self.calls))
        self.assertFalse(any('start' in call or 'restart' in call for call in self.calls))

    def test_active_docker_can_be_inspected_without_service_mutation(self):
        self.app.SYSTEMD_RUNTIME.mkdir()
        self.service_active = True
        self.assertTrue(self.app.inventory()[1])
        self.assertTrue(any('info' in call for call in self.calls))
        self.assertFalse(any('start' in call or 'restart' in call for call in self.calls))

    def test_snap_detection_does_not_activate_snapd(self):
        self.app.SNAP_DOCKER_CURRENT.mkdir()
        self.missing.discard('snap')
        with self.assertRaisesRegex(self.app.SetupError, 'Snap'):
            self.app.check_conflicts()
        self.assertFalse(any(call[0] == 'snap' for call in self.calls))

    def test_inherited_apt_hooks_and_host_specific_insecure_tls_are_excluded(self):
        inherited = self.root / 'inherited.conf'
        inherited.write_text('Acquire::https::download.docker.com::Verify-Peer "false";\nDPkg::Post-Invoke { "/bin/false"; };\nAPT::Update::Post-Invoke { "/bin/false"; };\n')
        with patch.dict(os.environ, {'APT_CONFIG': str(inherited)}), \
             patch.object(self.app, 'distro_sources', return_value=''), \
             self.app.apt_context(self.app.detect_system(), {}, ['gh']) as (apt_get, _):
            config = next((value.partition('=')[2] for value in apt_get if value.startswith('APT_CONFIG=')), str(inherited))
            result = subprocess.run(['apt-config', 'dump'], env={**os.environ, 'APT_CONFIG': config}, capture_output=True, text=True, check=True)
            self.assertNotIn('Verify-Peer "false"', result.stdout)
            self.assertNotIn('Post-Invoke', result.stdout)

    def test_existing_repository_is_never_updated_or_executes_preflight(self):
        self.app.REPO_DIR.mkdir()
        (self.app.REPO_DIR / ".git").mkdir()
        self.app.prepare_repository(False)
        self.assertFalse(any(set(call) & {"fetch", "pull", "reset", "clean", "checkout"} for call in self.calls))
        self.assertFalse(any("proxmox-preflight.sh" in str(call) for call in self.calls))

    def test_unknown_arguments_rejected_without_changes(self):
        with self.assertRaises(self.app.SetupError):
            self.app.main(["--check", "--upgrade"])
        self.assertFalse(self.calls)

    def test_fresh_ubuntu_install_and_second_run_do_not_touch_docker(self):
        self.missing.update({'docker', 'gh', 'trivy', 'git', 'jq'})
        for distro, code in [('ubuntu', 'resolute'), ('debian', 'trixie')]:
            with self.subTest(distro=distro):
                self.os_release.write_text(f'ID={distro}\nVERSION_CODENAME={code}\n')
                self.missing.update({'docker', 'gh', 'trivy', 'git', 'jq'})
                self.installed = {'ca-certificates': '1.0'}
                self.calls.clear()
                with patch.object(self.app, 'probe_repositories', return_value={}), \
                     patch.object(self.app, 'apt_context', return_value=contextlib.nullcontext((['apt-get'], ['apt-cache']))), \
                     patch.object(self.app, 'start_new_docker') as start:
                    code, _ = self.invoke([])
                    self.assertEqual(code, 0)
                    self.assertEqual(start.call_count, 1)
                    first_mutations = sum('install' in call and '--simulate' not in call for call in self.calls)
                    self.assertEqual(first_mutations, 1)
                    self.calls.clear()
                    start.reset_mock()
                    code, _ = self.invoke([])
                    self.assertEqual(code, 0)
                    start.assert_not_called()
                    self.assertFalse(any('install' in call or 'update' in call for call in self.calls))

    def test_upgrade_replaces_tools_but_not_functional_docker(self):
        self.installed.update({'gh': '1.0', 'trivy': '1.0', 'docker-ce': '2.0'})
        self.app.REPO_DIR.mkdir()
        (self.app.REPO_DIR / '.git').mkdir()
        self.apt_plan = 'Inst gh [1.0] (2.0 official [amd64])\nInst trivy [1.0] (2.0 official [amd64])\n'
        with patch.object(self.app, 'probe_repositories', return_value={}), \
             patch.object(self.app, 'apt_context', return_value=contextlib.nullcontext((['apt-get'], ['apt-cache']))):
            result, _ = self.invoke(['--upgrade'])
        self.assertEqual(result, 0)
        self.assertEqual(self.installed['gh'], '2.0')
        self.assertEqual(self.installed['docker-ce'], '2.0')

    def test_new_source_is_reused_without_duplicate_or_overwrite(self):
        system = self.app.detect_system()
        repo = self.app.repositories(system)[0]
        verified = {'docker': (repo, b'public-key')}
        with patch.object(self.app, 'secure_directory', side_effect=lambda path: path.mkdir(parents=True, exist_ok=True)):
            self.app.add_sources(system, verified, {}, ['docker-ce'])
        source = self.app.SOURCES_DIR / 'sporttracker-docker.sources'
        before = source.read_bytes()
        with patch.object(self.app, 'verify_key', return_value=b'public-key'), \
             patch.object(self.app, 'verify_release', return_value={'Suite': system.codename}), \
             patch.object(self.app, 'fetch_https', return_value=b'signed-release'):
            existing = self.app.existing_sources(system)
        self.app.add_sources(system, verified, existing, ['docker-ce'])
        self.assertEqual(source.read_bytes(), before)
        self.assertEqual(len(list(self.app.SOURCES_DIR.iterdir())), 1)

    def test_legacy_source_unsafe_options_are_not_accepted(self):
        self.app.SOURCES_LIST.write_text('deb [trusted=yes] https://download.docker.com/linux/ubuntu resolute stable\n')
        with self.assertRaisesRegex(self.app.SetupError, 'unsicher'):
            self.app.existing_sources(self.app.detect_system())

    def test_case_variant_trusted_option_cannot_bypass_source_validation(self):
        key = self.root / 'key.gpg'
        key.write_bytes(b'public-key')
        self.app.SOURCES_LIST.write_text(f'deb [signed-by={key} Trusted=yes] https://download.docker.com/linux/ubuntu resolute stable\n')
        with patch.object(self.app, 'verify_key', return_value=b'public-key'):
            with self.assertRaisesRegex(self.app.SetupError, 'unsicher'):
                self.app.existing_sources(self.app.detect_system())

    def test_apt_pins_installed_runtime_and_unrequested_dependencies(self):
        self.installed.update({'docker-ce': '29.9.2', 'libc6': '2.43', 'gh': '2.0'})
        with patch.object(self.app, 'distro_sources', return_value=''), \
             self.app.apt_context(self.app.detect_system(), {}, ['gh']) as (apt_get, _):
            pref = next(value.partition('=')[2] for value in apt_get if value.startswith('Dir::Etc::preferences='))
            text = Path(pref).read_text()
            self.assertIn('Package: docker-ce\nPin: version 29.9.2\nPin-Priority: 1001', text)
            self.assertIn('Package: libc6\nPin: version 2.43\nPin-Priority: 1001', text)
            self.assertNotIn('Pin: version 2.0', text)

    def test_no_indirect_unrequested_dependency_upgrade(self):
        self.apt_plan = 'Inst libc6 [1.0] (2.0 official [amd64])\n'
        with self.assertRaisesRegex(self.app.SetupError, 'Abhängigkeiten'):
            self.app.install_packages(['apt-get'], ['gh'], True)

    def test_failed_apt_simulation_never_calls_actual_install(self):
        self.apt_error = True
        with self.assertRaises(self.app.SetupError):
            self.app.install_packages(['apt-get'], ['gh'], False)
        self.assertEqual(sum('install' in call for call in self.calls), 1)

    def test_conflicting_distro_docker_requires_manual_migration(self):
        self.installed['docker.io'] = '29.0'
        with self.assertRaisesRegex(self.app.SetupError, 'Migration'):
            self.app.check_conflicts()
        self.assertFalse(any('remove' in call for call in self.calls))


class SignatureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = load_installer()
        cls.tmp = tempfile.TemporaryDirectory(prefix='host-setup-signatures-')
        cls.home = Path(cls.tmp.name)
        cls.home.chmod(0o700)
        cls.gpg = ['gpg', '--homedir', str(cls.home), '--batch', '--pinentry-mode', 'loopback', '--passphrase', '']
        subprocess.run([*cls.gpg, '--quick-generate-key', 'Isolated installer test', 'ed25519', 'sign', '0'],
                       check=True, capture_output=True)
        cls.key = subprocess.run([*cls.gpg, '--armor', '--export'], check=True, capture_output=True).stdout
        listing = subprocess.run([*cls.gpg, '--with-colons', '--list-keys'], check=True, capture_output=True).stdout.decode()
        cls.fingerprint = next(line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:'))
        cls.body = b'Date: Wed, 01 Jan 2020 00:00:00 UTC\nValid-Until: Thu, 01 Jan 2099 00:00:00 UTC\nArchitectures: amd64\n'
        cls.signed = subprocess.run([*cls.gpg, '--clearsign'], input=cls.body, check=True, capture_output=True).stdout

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['gpgconf', '--homedir', str(cls.home), '--kill', 'gpg-agent'], capture_output=True)
        cls.tmp.cleanup()

    def test_real_key_fingerprint_verified_without_creating_gpg_home(self):
        result = self.app.verify_key(self.key, {self.fingerprint})
        self.assertTrue(result)
        self.assertFalse(result.startswith(b'-----BEGIN'))

    def test_unknown_fingerprint_is_rejected(self):
        with self.assertRaisesRegex(self.app.SetupError, 'Fingerprint'):
            self.app.verify_key(self.key, {'0' * 40})

    def test_real_signed_metadata_is_verified(self):
        key = self.app.verify_key(self.key, {self.fingerprint})
        self.assertEqual(self.app.verify_release(key, self.signed)['Architectures'], 'amd64')

    def test_tampered_signature_is_fatal(self):
        key = self.app.verify_key(self.key, {self.fingerprint})
        with self.assertRaisesRegex(self.app.SetupError, 'Signatur'):
            self.app.verify_release(key, self.signed.replace(b'Architectures: amd64', b'Architectures: arm64'))

    def test_signed_package_index_digest_and_architecture_are_checked(self):
        import hashlib
        packages = b'Package: test-tool\nVersion: 99.0\nArchitecture: amd64\n\n'
        release = self.body + (f'Suite: stable\nSHA256:\n {hashlib.sha256(packages).hexdigest()} {len(packages)} main/binary-amd64/Packages\n').encode()
        signed = subprocess.run([*self.gpg, '--clearsign'], input=release, check=True, capture_output=True).stdout
        repo = dict(name='test', uri='https://official.invalid', suite='stable', component='main', key_url='https://official.invalid/key',
                    fingerprints={self.fingerprint}, packages=('test-tool',))
        def fetch(url, *args):
            return self.key if url.endswith('/key') else signed if url.endswith('/InRelease') else packages
        with patch.object(self.app, 'probe_distro'), patch.object(self.app, 'repositories', return_value=[repo]), patch.object(self.app, 'fetch_https', side_effect=fetch):
            self.assertIn('test', self.app.probe_repositories(self.app.System('debian', '13', 'trixie', 'amd64', 'none')))
            with self.assertRaisesRegex(self.app.SetupError, 'Architektur'):
                self.app.probe_repositories(self.app.System('debian', '13', 'trixie', 'unsupported', 'none'))
            with patch.object(self.app, 'fetch_https', side_effect=lambda url, *args: fetch(url, *args) if not url.endswith('/Packages') else b'tampered'):
                with self.assertRaisesRegex(self.app.SetupError, 'SHA256'):
                    self.app.probe_repositories(self.app.System('debian', '13', 'trixie', 'amd64', 'none'))

    def test_expired_signed_release_is_rejected(self):
        key = self.app.verify_key(self.key, {self.fingerprint})
        signed = subprocess.run([*self.gpg, '--clearsign'], input=self.body.replace(b'2099', b'2020'), check=True, capture_output=True).stdout
        with self.assertRaisesRegex(self.app.SetupError, 'Abgelaufene'):
            self.app.verify_release(key, signed)

    def test_http_download_is_rejected_before_network_access(self):
        with self.assertRaisesRegex(self.app.SetupError, 'HTTPS'):
            self.app.fetch_https('http://official.invalid/key')

    def test_signed_metadata_from_wrong_suite_is_rejected(self):
        packages = b'Package: test-tool\nVersion: 99.0\nArchitecture: amd64\n\n'
        import hashlib
        body = self.body + (f'Suite: wrong-release\nSHA256:\n {hashlib.sha256(packages).hexdigest()} {len(packages)} main/binary-amd64/Packages\n').encode()
        signed = subprocess.run([*self.gpg, '--clearsign'], input=body, check=True, capture_output=True).stdout
        repo = dict(name='test', uri='https://official.invalid', suite='stable', component='main', key_url='https://official.invalid/key',
                    fingerprints={self.fingerprint}, packages=('test-tool',))
        def fetch(url, *args):
            return self.key if url.endswith('/key') else signed if url.endswith('/InRelease') else packages
        with patch.object(self.app, 'probe_distro'), patch.object(self.app, 'repositories', return_value=[repo]), patch.object(self.app, 'fetch_https', side_effect=fetch):
            with self.assertRaisesRegex(self.app.SetupError, 'Suite|Release'):
                self.app.probe_repositories(self.app.System('debian', '13', 'trixie', 'amd64', 'none'))

    def test_distro_must_have_signed_release_for_the_actual_architecture(self):
        key_path = self.home / 'public.gpg'
        key_path.write_bytes(subprocess.run([*self.gpg, '--export'], check=True, capture_output=True).stdout)
        body = self.body + b'Codename: trixie\n'
        signed = subprocess.run([*self.gpg, '--clearsign'], input=body, check=True, capture_output=True).stdout
        with patch.object(self.app, 'distro_repositories', return_value=[('https://official.invalid/debian', 'trixie', key_path)]), \
             patch.object(self.app, 'fetch_https', return_value=signed):
            self.app.probe_distro(self.app.System('debian', '13', 'trixie', 'amd64', 'none'))
            with self.assertRaisesRegex(self.app.SetupError, 'Architektur'):
                self.app.probe_distro(self.app.System('debian', '13', 'trixie', 'not-supported', 'none'))

    def test_existing_keyring_must_verify_current_repository_signature(self):
        sources = self.home / 'existing-sources'
        sources.mkdir(exist_ok=True)
        keyfile = self.home / 'existing-public.asc'
        keyfile.write_bytes(self.key)
        (sources / 'vendor.list').write_text(f'deb [signed-by={keyfile}] https://official.invalid stable main\n')
        repo = dict(name='test', uri='https://official.invalid', suite='stable', component='main', fingerprints={self.fingerprint})
        with patch.object(self.app, 'SOURCES_DIR', sources), patch.object(self.app, 'SOURCES_LIST', self.home / 'absent.list'), \
             patch.object(self.app, 'repositories', return_value=[repo]), \
             patch.object(self.app, 'fetch_https', return_value=self.signed.replace(b'Architectures: amd64', b'Architectures: arm64')):
            with self.assertRaisesRegex(self.app.SetupError, 'Signatur'):
                self.app.existing_sources(self.app.System('debian', '13', 'trixie', 'amd64', 'none'))


class GitIsolationTests(unittest.TestCase):
    def test_existing_checkout_filters_are_never_executed(self):
        app = load_installer()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            app.REPO_DIR = root / 'repo'
            subprocess.run(['git', 'init', str(app.REPO_DIR)], check=True, capture_output=True)
            git = ['git', '-C', str(app.REPO_DIR)]
            subprocess.run([*git, 'remote', 'add', 'origin', app.REPO_URL], check=True, capture_output=True)
            (app.REPO_DIR / '.gitattributes').write_text('tracked filter=untrusted\n')
            tracked = app.REPO_DIR / 'tracked'
            tracked.write_text('original\n')
            subprocess.run([*git, 'add', '.'], check=True, capture_output=True)
            marker = root / 'filter-executed'
            subprocess.run([*git, 'config', 'filter.untrusted.clean', f'touch {marker}; cat'], check=True, capture_output=True)
            tracked.write_text('modified\n')  # Same size forces Git to compare content rather than just stat size.
            os.utime(tracked, (tracked.stat().st_atime + 2, tracked.stat().st_mtime + 2))
            app.prepare_repository(True)
            self.assertFalse(marker.exists(), 'Read-only repository check executed a repository-configured filter')

    def test_clone_ignores_inherited_git_rewrites_and_credential_helpers(self):
        app = load_installer()
        with tempfile.TemporaryDirectory() as tmp:
            app.REPO_DIR = Path(tmp) / 'repo'
            calls = []
            def fake(args, **kwargs):
                calls.append((args, kwargs.get('env', {})))
                return subprocess.CompletedProcess(args, 0, '', '')
            with patch.dict(os.environ, {'GIT_CONFIG_PARAMETERS': "'url.ssh://attacker/.insteadof=https://github.com/'", 'GIT_CONFIG_COUNT': '1',
                                         'GIT_CONFIG_KEY_0': 'credential.helper', 'GIT_CONFIG_VALUE_0': 'untrusted-helper'}), patch.object(app, 'run', side_effect=fake):
                app.prepare_repository(False)
            args, env = calls[0]
            self.assertNotIn('GIT_CONFIG_PARAMETERS', env)
            self.assertNotIn('GIT_CONFIG_VALUE_0', env)
            self.assertEqual(env.get('GIT_CONFIG_GLOBAL'), '/dev/null')
            self.assertEqual(env.get('GIT_CONFIG_NOSYSTEM'), '1')
            self.assertIn('protocol.allow=never', args)
            self.assertIn('protocol.https.allow=always', args)
            self.assertIn('http.sslVerify=true', args)


if __name__ == "__main__":
    unittest.main()
