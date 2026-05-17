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
