<template>
  <a class="skip-link" href="#main-content">Zum Inhalt springen</a>
  <AppNavigation />
  <main id="main-content" class="app-main" tabindex="-1">
    <RouterView />
  </main>
  <footer class="app-footer">
    <VersionIndicator />
  </footer>
</template>

<script setup lang="ts">
import AppNavigation from './components/AppNavigation.vue'
import VersionIndicator from './components/VersionIndicator.vue'
</script>
