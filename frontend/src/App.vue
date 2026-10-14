<template>
  <a class="skip-link" href="#main-content">Zum Inhalt springen</a>
  <AppNavigation />
  <main id="main-content" class="app-main" tabindex="-1">
    <RouterView />
  </main>
</template>

<script setup lang="ts">
import AppNavigation from './components/AppNavigation.vue'
</script>
