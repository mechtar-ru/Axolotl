<template>
  <nav class="navbar" v-if="!isLoginPage">
    <div class="nav-left">
      <router-link to="/" class="nav-brand">
        <svg class="nav-logo" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/>
        </svg>
        <span class="nav-title">Axolotl</span>
      </router-link>
      <div class="nav-links">
        <router-link to="/" class="nav-link" :class="{ active: $route.name === 'dashboard' }">
          Dashboard
        </router-link>
        <router-link to="/about" class="nav-link" :class="{ active: $route.name === 'about' }">
          About
        </router-link>
        <router-link v-if="authStore.isAuthenticated" to="/settings" class="nav-link" :class="{ active: $route.name === 'settings' }">
          Settings
        </router-link>
      </div>
    </div>
    <div class="nav-right">
      <template v-if="authStore.isAuthenticated">
        <span class="nav-username">{{ authStore.username }}</span>
        <button class="nav-btn nav-logout" @click="handleLogout">Sign out</button>
      </template>
      <router-link v-else to="/login" class="nav-btn nav-signin">Sign in</router-link>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/authStore'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const isLoginPage = computed(() => route.name === 'login')

function handleLogout() {
  authStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.navbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 1.25rem;
  background: var(--bg-secondary, #1e1e2e);
  border-bottom: 1px solid var(--border-color, #4a4a6a);
  flex-shrink: 0;
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 1.5rem;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  text-decoration: none;
  color: var(--text-primary, #eee);
  font-weight: 700;
  font-size: 0.95rem;
}

.nav-logo {
  width: 20px;
  height: 20px;
  color: var(--accent, #7b5cff);
}

.nav-title {
  letter-spacing: 0.3px;
}

.nav-links {
  display: flex;
  align-items: center;
  gap: 0.25rem;
}

.nav-link {
  text-decoration: none;
  color: var(--text-secondary, #888);
  font-size: 0.85rem;
  padding: 0.375rem 0.75rem;
  border-radius: 6px;
  transition: all 0.15s;
}

.nav-link:hover {
  color: var(--text-primary, #eee);
  background: var(--bg-hover, rgba(255,255,255,0.06));
}

.nav-link.active {
  color: var(--accent, #7b5cff);
  background: var(--accent-bg, rgba(123,92,255,0.1));
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.nav-username {
  font-size: 0.8rem;
  color: var(--text-muted, #666);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-btn {
  text-decoration: none;
  font-size: 0.8rem;
  font-weight: 600;
  padding: 0.375rem 0.875rem;
  border-radius: 6px;
  border: none;
  cursor: pointer;
  transition: all 0.15s;
}

.nav-signin {
  background: var(--accent, #7b5cff);
  color: #fff;
}

.nav-signin:hover {
  opacity: 0.9;
}

.nav-logout {
  background: transparent;
  color: var(--text-secondary, #888);
  border: 1px solid var(--border-color, #4a4a6a);
}

.nav-logout:hover {
  color: var(--error, #ff6b6b);
  border-color: var(--error, #ff6b6b);
  background: rgba(255, 107, 107, 0.08);
}
</style>
