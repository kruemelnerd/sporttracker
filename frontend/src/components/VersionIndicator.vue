<template>
  <details ref="disclosure" class="version-indicator" @keydown.esc="closeDetails">
    <summary :id="summaryId" :aria-controls="panelId">
      <span>{{ version?.applicationVersion ? `Version ${version.applicationVersion}` : 'Version nicht verfügbar' }}</span>
      <span class="version-status" role="status">{{ loading ? (version ? 'Prüfung läuft …' : 'Versionsinformationen werden geladen …') : statusText }}</span>
    </summary>
    <div :id="panelId" class="version-panel" role="region" :aria-labelledby="summaryId">
      <h2>Versionsinformationen</h2>
      <p v-if="errorMessage" class="version-error" data-testid="version-error">{{ errorMessage }}</p>
      <div class="version-releases">
        <section v-for="release in releases" :key="release.title" :aria-label="release.title">
          <h3>{{ release.title }}</h3>
          <dl>
            <div v-if="release.installed">
              <dt>Anwendungsversion</dt>
              <dd>{{ version?.applicationVersion || 'Nicht verfügbar' }}</dd>
            </div>
            <div>
              <dt>Git-Commit</dt>
              <dd>
                <a v-if="release.commit" :href="`https://github.com/kruemelnerd/sporttracker/commit/${encodeURIComponent(release.commit)}`"
                  target="_blank" rel="noopener noreferrer" :aria-label="`${release.title}: Commit ${release.commit.slice(0, 7)} auf GitHub (neuer Tab)`">
                  {{ release.commit.slice(0, 7) }}
                </a>
                <span v-else>Commit nicht verfügbar</span>
              </dd>
            </div>
            <div>
              <dt>Image-Digest</dt>
              <dd v-if="release.digest">
                <code>{{ release.digest }}</code>
                <button type="button" :aria-label="release.installed ? 'Installierten Digest kopieren' : 'Registry-Digest kopieren'"
                  @click="copyDigest(release.digest, release.installed)">Digest kopieren</button>
              </dd>
              <dd v-else>{{ release.missingDigest }}</dd>
            </div>
          </dl>
        </section>
      </div>
      <p>Letzte Registry-Prüfung:
        <time v-if="checkedAt" :datetime="version?.lastCheckedAt ?? undefined">{{ checkedAt }}</time>
        <span v-else>Noch nicht geprüft</span>
      </p>
      <button type="button" data-testid="version-refresh" :disabled="loading" :aria-busy="loading" @click="loadVersion(true)">
        {{ loading ? 'Prüfung läuft …' : 'Auf Updates prüfen' }}
      </button>
      <p class="version-hint">Höchstens eine Prüfung pro Minute. Innerhalb dieser Zeit wird der letzte Prüfstand angezeigt. Es werden keine Updates installiert oder Images heruntergeladen.</p>
      <p class="version-feedback" data-testid="version-feedback" role="status">{{ feedback }}</p>
    </div>
  </details>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, useId } from 'vue'
import { getVersion, type VersionRegistryError, type VersionResponse, type VersionStatus } from '../api'

const version = ref<VersionResponse | null>(null)
const loading = ref(false)
const requestFailed = ref(false)
const feedback = ref('')
const disclosure = ref<HTMLDetailsElement | null>(null)
const summaryId = useId()
const panelId = useId()
const labels: Record<VersionStatus, string> = {
  CURRENT: 'Version aktuell', UPDATE_AVAILABLE: 'Update verfügbar', REBUILD_AVAILABLE: 'Neuer Build verfügbar',
  DIFFERENT_RELEASE: 'Anderes Release veröffentlicht', UNKNOWN: 'Update-Status unbekannt',
}
const registryErrors: Record<VersionRegistryError, string> = {
  AUTHENTICATION_REQUIRED: 'Die Registry verlangt eine Anmeldung. Der Update-Status ist derzeit nicht verfügbar.',
  ACCESS_DENIED: 'Die Registry hat den Zugriff verweigert. Der Update-Status ist derzeit nicht verfügbar.',
  NOT_FOUND: 'Das veröffentlichte Release wurde in der Registry nicht gefunden.',
  RATE_LIMITED: 'Zu viele Registry-Anfragen. Bitte später erneut prüfen.',
  REGISTRY_UNAVAILABLE: 'Die Registry ist derzeit nicht erreichbar. Bitte später erneut prüfen.',
  TIMEOUT: 'Zeitüberschreitung bei der Registry-Prüfung. Bitte später erneut prüfen.',
  NETWORK_ERROR: 'Die Registry ist wegen eines Netzwerkfehlers nicht erreichbar.',
  INVALID_RESPONSE: 'Die Registry hat eine ungültige Antwort geliefert. Bitte später erneut prüfen.',
}
const statusText = computed(() => labels[requestFailed.value || version.value?.errorStatus ? 'UNKNOWN' : version.value?.updateStatus ?? 'UNKNOWN'] ?? labels.UNKNOWN)
const errorMessage = computed(() => requestFailed.value
  ? 'Versionsinformationen konnten nicht geladen werden. Bitte später erneut versuchen.'
  : version.value?.errorStatus ? registryErrors[version.value.errorStatus] ?? 'Der Update-Status ist derzeit nicht verfügbar.' : '')
const releases = computed(() => [
  { title: 'Installierte Version', installed: true, commit: version.value?.installedCommit, digest: version.value?.installedDigest,
    missingDigest: 'Für diese Installation ist kein Image-Digest verfügbar.' },
  { title: 'Veröffentlichtes Release', installed: false,
    commit: requestFailed.value ? null : version.value?.latestCommit, digest: requestFailed.value ? null : version.value?.latestDigest,
    missingDigest: 'Registry-Digest nicht verfügbar.' },
])
const checkedAt = computed(() => {
  const value = version.value?.lastCheckedAt
  if (!value || !Number.isFinite(Date.parse(value))) return null
  return new Intl.DateTimeFormat('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
})

async function loadVersion(refresh = false) {
  if (loading.value) return
  loading.value = true
  feedback.value = ''
  try {
    version.value = await getVersion(refresh)
    requestFailed.value = false
  } catch {
    requestFailed.value = true
  } finally {
    loading.value = false
  }
}

async function copyDigest(digest: string, installed: boolean) {
  feedback.value = ''
  try {
    await navigator.clipboard.writeText(digest)
    feedback.value = installed ? 'Installierter Digest kopiert.' : 'Registry-Digest kopiert.'
  } catch {
    feedback.value = 'Kopieren nicht möglich. Bitte den Digest markieren und manuell kopieren.'
  }
}

function closeDetails() {
  if (!disclosure.value?.open) return
  disclosure.value.open = false
  disclosure.value.querySelector('summary')?.focus()
}

onMounted(() => loadVersion())
</script>

<style scoped>
.version-indicator { color: var(--color-muted); font-size: 0.875rem; }
summary { min-height: 44px; padding: 0.625rem 0; cursor: pointer; }
summary:hover { color: var(--color-accent-strong); }
.version-status { margin-left: 0.75rem; color: var(--color-accent-strong); }
.version-panel { padding: 1rem; border: 1px solid var(--color-border); border-radius: var(--radius-card); background: var(--color-surface); color: var(--color-text); }
h2 { margin: 0 0 1rem; font-size: 1.125rem; }
h3 { margin: 0 0 0.75rem; font-size: 1rem; }
.version-releases { display: grid; gap: 1.5rem; }
.version-releases section { min-width: 0; }
dl { margin: 0; }
dl > div + div { margin-top: 0.75rem; }
dt { color: var(--color-muted); }
dd { margin: 0.25rem 0 0; }
dd a { display: inline-flex; align-items: center; min-height: 44px; min-width: 44px; }
code { display: block; overflow-wrap: anywhere; user-select: all; }
button { min-height: 44px; padding: 0.5rem 0.75rem; border: 1px solid var(--color-accent-strong); border-radius: var(--radius-control); color: var(--color-accent-strong); background: var(--color-accent-subtle); cursor: pointer; }
button:hover:not(:disabled) { background: var(--color-canvas); }
button:disabled { opacity: 0.65; cursor: wait; }
dd button { margin-top: 0.5rem; }
.version-hint { color: var(--color-muted); }
.version-error { border-left: 3px solid var(--color-warning); padding-left: 0.75rem; }
.version-feedback:empty { margin: 0; }
@media (min-width: 768px) { .version-releases { grid-template-columns: 1fr 1fr; } }
</style>
