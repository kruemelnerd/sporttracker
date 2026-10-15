import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from './views/DashboardView.vue'
import ImportView from './views/ImportView.vue'
import SummaryView from './views/SummaryView.vue'
import TrainingPlanView from './views/TrainingPlanView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView },
    { path: '/fixture-summary', name: 'fixture-summary', component: SummaryView },
    { path: '/import', name: 'import', component: ImportView },
    { path: '/training-plan', name: 'training-plan', component: TrainingPlanView },
    { path: '/activities/:id', name: 'activity', component: () => import('./views/ActivityDetailView.vue') },
  ],
})
