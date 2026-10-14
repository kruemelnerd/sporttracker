import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from './views/DashboardView.vue'
import ImportView from './views/ImportView.vue'
import SummaryView from './views/SummaryView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView },
    { path: '/fixture-summary', name: 'fixture-summary', component: SummaryView },
    { path: '/import', name: 'import', component: ImportView },
    { path: '/activities/:id', name: 'activity', component: () => import('./views/ActivityPlaceholderView.vue') },
  ],
})
