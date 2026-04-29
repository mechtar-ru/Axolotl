import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
    },
    {
      path: '/',
      name: 'home',
      component: HomeView,
      meta: { requiresAuth: true },
    },
    {
      path: '/schema/:id',
      name: 'schema',
      component: HomeView,
      meta: { requiresAuth: true },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('../views/SettingsView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/about',
      name: 'about',
      component: () => import('../views/AboutView.vue'),
    },
    // Catch-all route for 404s
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    },
  ],
})

router.beforeEach((to, _from) => {
  const token = localStorage.getItem('axolotl_token');
  console.log('[Router] Navigation to:', to.path, '| requiresAuth:', to.meta.requiresAuth, '| hasToken:', !!token);
  if (to.meta.requiresAuth && !token) {
    console.log('[Router] No token, redirecting to login');
    return { name: 'login' };
  }
  if (to.name === 'login' && token) {
    console.log('[Router] Already logged in, redirecting to home');
    return { name: 'home' };
  }
});

export default router
