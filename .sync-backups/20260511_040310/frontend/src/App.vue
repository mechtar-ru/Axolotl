<template>
  <router-view />
  <ToastContainer />
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { useSchemaStore } from './stores/schemaStore';
import ToastContainer from './components/ToastContainer.vue';

const schemaStore = useSchemaStore();

onMounted(() => {
  schemaStore.loadSchemas();
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
  background: #1a1a2e;
  color: #eee;
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
</style>
