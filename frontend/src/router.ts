import { createRouter, createWebHistory } from 'vue-router'
import SummaryView from './views/SummaryView.vue'
import ImportView from './views/ImportView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [{ path: '/', name: 'summary', component: SummaryView }, { path: '/import', name: 'import', component: ImportView }],
})
