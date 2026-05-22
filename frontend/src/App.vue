<template>
  <div class="app-layout">
    <NavBar />
    <main class="app-main">
      <router-view v-slot="{ Component, route }">
        <transition name="fade" mode="out-in">
          <keep-alive include="dashboard,studio,app-dashboard,settings">
            <component :is="Component" :key="route.path" />
          </keep-alive>
        </transition>
      </router-view>
    </main>
    <ToastContainer />
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import NavBar from './components/NavBar.vue';
import ToastContainer from './components/ToastContainer.vue';
import { useSettingsStore } from './stores/settingsStore';

const settingsStore = useSettingsStore();

onMounted(() => {
  settingsStore.initTheme();
  // Pre-fetch provider model lists so Studio and other components
  // have models available without waiting for Settings page load
  settingsStore.fetchProviders();
});
</script>

<style>
body {
  font-family: var(--font-sans, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif);
  background: var(--bg-primary, #1a1a2e);
  color: var(--text-primary, #eee);
}

.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.app-main {
  flex: 1;
  overflow: auto;
}

.vue-flow__node {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
}

*:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.mono {
  font-family: var(--font-mono);
}

/* Shared button styles */
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--accent-gradient);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: opacity var(--transition), transform 0.1s;
  line-height: 1.4;
}
.btn-primary:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}
.btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--bg-secondary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: background var(--transition);
  line-height: 1.4;
}
.btn-secondary:hover {
  background: var(--bg-hover);
}
.btn-secondary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-danger {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--error);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition);
  line-height: 1.4;
}
.btn-danger:hover {
  background: var(--error-hover);
}
.btn-danger:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.icon {
  width: var(--icon-sm);
  height: var(--icon-sm);
}

/* Route transitions */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
