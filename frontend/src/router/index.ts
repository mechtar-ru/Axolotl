import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '@/views/DashboardView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
    },
    {
      path: '/',
      name: 'dashboard',
      component: DashboardView,
      meta: { requiresAuth: true },
    },
    {
      path: '/app/:id',
      name: 'studio',
      component: () => import('@/views/StudioView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/app/:id/home',
      name: 'app-dashboard',
      component: () => import('@/views/AppDashboardView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/schema/:id',
      redirect: (to) => ({ path: `/app/${to.params.id}` }),
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/about',
      name: 'about',
      component: () => import('@/views/AboutView.vue'),
    },
  ],
})

router.beforeEach((to, _from) => {
  const token = localStorage.getItem('axolotl_token');
  if (to.meta.requiresAuth && !token) return { name: 'login' };
  if (to.name === 'login' && token) return { name: 'dashboard' };
});

export default router
