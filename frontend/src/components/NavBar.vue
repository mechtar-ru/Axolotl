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
  height: var(--toolbar-height);
  padding: 0 var(--space-5);
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.nav-left {
  display: flex;
  align-items: center;
  gap: var(--space-5);
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  text-decoration: none;
  color: var(--text-primary);
  font-weight: 700;
  font-size: var(--text-base);
}

.nav-logo {
  width: 20px;
  height: 20px;
  color: var(--accent);
}

.nav-title {
  letter-spacing: 0.3px;
}

.nav-links {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.nav-link {
  text-decoration: none;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.nav-link:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

.nav-link.active {
  color: var(--accent);
  background: var(--accent-bg);
}

.nav-right {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.nav-username {
  font-size: var(--text-xs);
  color: var(--text-muted);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-btn {
  text-decoration: none;
  font-size: var(--text-xs);
  font-weight: 600;
  padding: var(--space-1) var(--space-4);
  border-radius: var(--radius-sm);
  border: none;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.nav-signin {
  background: var(--accent);
  color: #fff;
}

.nav-signin:hover {
  opacity: 0.9;
}

.nav-logout {
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
}

.nav-logout:hover {
  color: var(--error);
  border-color: var(--error);
  background: rgba(255, 107, 107, 0.08);
}
</style>
