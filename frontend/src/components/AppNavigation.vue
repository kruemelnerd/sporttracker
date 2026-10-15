<template>
  <header class="app-header">
    <RouterLink class="brand" to="/" aria-label="Pacebase – Übersicht">PACEBASE</RouterLink>
    <button
      class="menu-toggle"
      type="button"
      aria-controls="primary-navigation"
      :aria-expanded="menuOpen"
      @click="menuOpen = !menuOpen"
    >
      <span>{{ menuOpen ? 'Menü schließen' : 'Menü öffnen' }}</span>
    </button>
    <nav id="primary-navigation" class="primary-navigation" aria-label="Hauptnavigation" :class="{ 'is-open': menuOpen }">
      <RouterLink to="/" :aria-current="isCurrent('dashboard') ? 'page' : undefined" @click="menuOpen = false">
        Übersicht
      </RouterLink>
      <RouterLink to="/import" :aria-current="isCurrent('import') ? 'page' : undefined" @click="menuOpen = false">
        Import
      </RouterLink>
      <RouterLink to="/training-plan" :aria-current="isCurrent('training-plan') ? 'page' : undefined" @click="menuOpen = false">
        Trainingsplan
      </RouterLink>
    </nav>
  </header>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

const route = useRoute()
const menuOpen = ref(false)
const isCurrent = (name: string) => route.name === name || (name === 'dashboard' && route.name === 'activity')
</script>
