<template>
  <router-view />
  <ToastContainer />
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import ToastContainer from './components/ToastContainer.vue';
import { useSettingsStore } from './stores/settingsStore';

const settingsStore = useSettingsStore();

onMounted(() => {
  settingsStore.initTheme();
});
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  background: var(--bg-primary, #1a1a2e);
  color: var(--text-primary, #eee);
}

.app {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  width: 250px;
  background: #2d2d44;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 15px;
  overflow-y: auto;
}

.sidebar h2 {
  font-size: 18px;
  margin-bottom: 10px;
}

.sidebar ul {
  list-style: none;
}

.sidebar li {
  padding: 10px;
  margin: 5px 0;
  background: #1a1a2e;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.sidebar li:hover {
  background: #3d3d5c;
}

.sidebar li.active {
  background: #6c63ff;
}

.sidebar button {
  padding: 10px;
  background: #6c63ff;
  border: none;
  border-radius: 6px;
  color: white;
  cursor: pointer;
}

.import-btn {
  background: #4caf50 !important;
}

.canvas-container {
  flex: 1;
  position: relative;
  background: #1a1a2e;
}

.placeholder {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  color: #888;
  font-size: 18px;
}

.toolbar {
  position: fixed;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 10px;
  background: #2d2d44;
  padding: 10px 20px;
  border-radius: 12px;
  z-index: 1000;
}

.toolbar button {
  padding: 8px 16px;
  background: #6c63ff;
  border: none;
  border-radius: 6px;
  color: white;
  cursor: pointer;
}

.modal {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0,0,0,0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 2000;
}

.modal-content {
  background: #2d2d44;
  padding: 20px;
  border-radius: 12px;
  max-width: 80%;
  max-height: 80%;
  overflow: auto;
}

.modal-content pre {
  background: #1a1a2e;
  padding: 15px;
  border-radius: 8px;
  overflow: auto;
  margin: 15px 0;
}

.modal-buttons {
  display: flex;
  gap: 10px;
  justify-content: center;
}

.modal-buttons button {
  padding: 8px 16px;
  background: #6c63ff;
  border: none;
  border-radius: 6px;
  color: white;
  cursor: pointer;
}

.vue-flow__node {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
}

:root {
  --border: #4a4a6a;
  --border-focus: #6c63ff;
  --border-subtle: rgba(255,255,255,0.08);
  --error: #ff6b6b;
  --success: #4caf50;
  --radius-sm: 6px;
  --radius-md: 10px;
  --font-mono: 'SF Mono', 'Fira Code', Consolas, monospace;
}

[data-theme="light"] {
  --bg-primary: #f8f9fa;
  --bg-secondary: #ffffff;
  --bg-card: #ffffff;
  --bg-sidebar: #f1f3f5;
  --bg-canvas: #f8f9fa;
  --bg-hover: #e9ecef;
  --bg-active: #dee2e6;
  --text-primary: #1a1a2e;
  --text-secondary: #6b7280;
  --text-muted: #9ca3af;
  --border-color: #e5e7eb;
  --border-light: #f0f0f0;
  --accent: #6c63ff;
  --accent-light: #8b83ff;
  --accent-bg: #edebff;
  --shadow-sm: 0 1px 2px rgba(0,0,0,0.05);
  --shadow-md: 0 4px 6px rgba(0,0,0,0.07);
  --shadow-lg: 0 10px 15px rgba(0,0,0,0.1);

  --border: #e5e7eb;
  --border-focus: #6c63ff;
  --border-subtle: rgba(0,0,0,0.08);
  --error: #dc3545;
  --success: #28a745;
  --radius-sm: 6px;
  --radius-md: 10px;
  --font-mono: 'SF Mono', 'Fira Code', Consolas, monospace;
}
</style>
