<template>
  <div v-if="visible" class="memory-graph-panel">
    <div class="memory-graph-header">
      <span>🧠 Граф памяти</span>
      <div class="header-actions">
        <button class="refresh-btn" @click="loadGraph" :disabled="loading">🔄</button>
        <button class="close-btn" @click="$emit('close')">✕</button>
      </div>
    </div>

    <div v-if="loading" class="loading-state">Загрузка графа...</div>
    <div v-else-if="error" class="error-state">{{ error }}</div>

    <div v-else class="memory-graph-body">
      <!-- Wings overview -->
      <div class="wings-overview">
        <div
          v-for="wing in wings"
          :key="wing"
          class="wing-card"
          :class="{ active: selectedWing === wing }"
          @click="selectedWing = selectedWing === wing ? null : wing"
        >
          <span class="wing-icon">{{ wingIcons[wing] || '📁' }}</span>
          <span class="wing-name">{{ wing }}</span>
          <span class="wing-count">{{ wingRooms[wing]?.length || 0 }} rooms</span>
        </div>
        <div v-if="wings.length === 0" class="empty-state">
          Нет сохранённых воспоминаний. Запустите схему с агентом — результаты сохранятся автоматически.
        </div>
      </div>

      <!-- Rooms for selected wing -->
      <div v-if="selectedWing" class="rooms-section">
        <h3>Комнаты: {{ selectedWing }}</h3>
        <div class="rooms-list">
          <div
            v-for="room in wingRooms[selectedWing] || []"
            :key="room"
            class="room-card"
            @click="loadDrawers(selectedWing, room)"
          >
            <span class="room-icon">🗄️</span>
            <span class="room-name">{{ room }}</span>
          </div>
        </div>
      </div>

      <!-- Drawers (memory entries) -->
      <div v-if="selectedRoom" class="drawers-section">
        <h3>{{ selectedRoom }} — {{ drawers.length }} записей</h3>
        <div class="drawers-list">
          <div
            v-for="drawer in drawers"
            :key="drawer.id"
            class="drawer-card"
            @click="viewDrawer(drawer)"
          >
            <div class="drawer-preview">{{ drawer.contentPreview }}</div>
            <div class="drawer-meta">
              <span class="drawer-wing">{{ drawer.wing }}</span>
              <span class="drawer-room">{{ drawer.room }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Tunnels (connections between wings) -->
      <div v-if="selectedWing && tunnels.length > 0" class="tunnels-section">
        <h3>🔗 Туннели (связи с другими крыльями)</h3>
        <div class="tunnels-list">
          <div
            v-for="tunnel in tunnels"
            :key="tunnel.room"
            class="tunnel-card"
            @click="selectRoomFromTunnel(tunnel)"
          >
            <span class="tunnel-icon">🚇</span>
            <span class="tunnel-text">{{ tunnel.room }} → {{ tunnel.connectedWing }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Drawer detail modal -->
    <div v-if="viewingDrawer" class="drawer-modal-overlay" @click.self="viewingDrawer = null">
      <div class="drawer-modal">
        <div class="drawer-modal-header">
          <span>{{ viewingDrawer.wing }} / {{ viewingDrawer.room }}</span>
          <button class="close-modal-btn" @click="viewingDrawer = null">✕</button>
        </div>
        <div class="drawer-modal-body">
          <pre class="drawer-content">{{ viewingDrawer.content }}</pre>
        </div>
        <div v-if="viewingDrawer.sourceFile" class="drawer-modal-footer">
          Источник: {{ viewingDrawer.sourceFile }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

const visibleProp = defineProps<{ visible: boolean }>();
defineEmits<{ (e: 'close'): void }>();

const loading = ref(false);
const error = ref('');

const wings = ref<string[]>([]);
const wingRooms = ref<Record<string, string[]>>({});
const selectedWing = ref<string | null>(null);
const selectedRoom = ref<string | null>(null);
const drawers = ref<Array<{ id: string; wing: string; room: string; content: string; contentPreview: string; sourceFile?: string }>>([]);
const tunnels = ref<Array<{ room: string; connectedWing: string }>>([]);

const viewingDrawer = ref<{ wing: string; room: string; content: string; sourceFile?: string } | null>(null);

const wingIcons: Record<string, string> = {
  axolotl: '🦎',
  code: '💻',
  user: '👤',
  team: '👥',
  myproject: '📋',
};

async function loadGraph() {
  loading.value = true;
  error.value = '';
  try {
    const response = await fetch(`${API_BASE_URL}/memory/taxonomy`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const taxonomy: Record<string, Record<string, number>> = await response.json();
    wings.value = Object.keys(taxonomy).sort();
    wingRooms.value = {};
    for (const [wing, rooms] of Object.entries(taxonomy)) {
      wingRooms.value[wing] = Object.keys(rooms).sort();
    }
  } catch (e: any) {
    error.value = 'Ошибка загрузки графа: ' + (e.message || e);
  } finally {
    loading.value = false;
  }
}

async function loadDrawers(wing: string, room: string) {
  selectedRoom.value = room;
  try {
    const response = await fetch(`${API_BASE_URL}/memory/drawers?wing=${encodeURIComponent(wing)}&room=${encodeURIComponent(room)}`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const items: Array<{ id: string; wing: string; room: string; content: string; source_file?: string }> = await response.json();
    drawers.value = items.map(d => ({
      ...d,
      sourceFile: d.source_file,
      contentPreview: d.content.length > 120 ? d.content.substring(0, 120) + '...' : d.content,
    }));
  } catch (e: any) {
    console.error('Failed to load drawers:', e);
    drawers.value = [];
  }
}

function viewDrawer(drawer: { wing: string; room: string; content: string; sourceFile?: string }) {
  viewingDrawer.value = {
    wing: drawer.wing,
    room: drawer.room,
    content: drawer.content,
    sourceFile: drawer.sourceFile,
  };
}

async function loadTunnels() {
  if (!selectedWing.value) return;
  try {
    const response = await fetch(`${API_BASE_URL}/memory/tunnels?wing_a=${encodeURIComponent(selectedWing.value)}`);
    if (!response.ok) return;
    const data = await response.json();
    tunnels.value = (data.tunnels || []).map((t: any) => ({
      room: t.room,
      connectedWing: t.connectedWing,
    }));
  } catch {
    tunnels.value = [];
  }
}

function selectRoomFromTunnel(tunnel: { room: string; connectedWing: string }) {
  selectedWing.value = tunnel.connectedWing;
  selectedRoom.value = tunnel.room;
  loadDrawers(tunnel.connectedWing, tunnel.room);
}

watch(selectedWing, (newWing) => {
  selectedRoom.value = null;
  drawers.value = [];
  if (newWing) loadTunnels();
  else tunnels.value = [];
});

watch(() => visibleProp.visible, (v) => {
  if (v) loadGraph();
});
</script>

<style scoped>
.memory-graph-panel {
  width: 100%;
  height: 100%;
  background: rgba(19, 19, 31, 0.97);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.memory-graph-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  font-weight: 600;
  color: #eee;
  font-size: 15px;
  position: sticky;
  top: 0;
  background: rgba(19, 19, 31, 0.97);
  z-index: 1;
}

.header-actions {
  display: flex;
  gap: 6px;
}

.refresh-btn,
.close-btn {
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: #eee;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}

.refresh-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.loading-state,
.error-state,
.empty-state {
  padding: 30px 16px;
  text-align: center;
  color: #888;
  font-size: 14px;
}

.error-state {
  color: #ff6b6b;
}

.memory-graph-body {
  flex: 1;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.wings-overview {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.wing-card {
  background: rgba(108, 99, 255, 0.1);
  border: 1px solid rgba(108, 99, 255, 0.25);
  border-radius: 8px;
  padding: 8px 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.2s;
}

.wing-card:hover {
  background: rgba(108, 99, 255, 0.2);
}

.wing-card.active {
  background: rgba(108, 99, 255, 0.3);
  border-color: #6c63ff;
}

.wing-icon {
  font-size: 16px;
}

.wing-name {
  font-size: 13px;
  color: #ddd;
  font-weight: 500;
}

.wing-count {
  font-size: 11px;
  color: #888;
}

.rooms-section,
.drawers-section,
.tunnels-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rooms-section h3,
.drawers-section h3,
.tunnels-section h3 {
  font-size: 13px;
  color: #aaa;
  margin: 0;
  padding: 4px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.rooms-list,
.drawers-list,
.tunnels-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.room-card,
.drawer-card,
.tunnel-card {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 6px;
  padding: 8px 10px;
  cursor: pointer;
  transition: background 0.2s;
  display: flex;
  align-items: center;
  gap: 8px;
}

.room-card:hover,
.drawer-card:hover,
.tunnel-card:hover {
  background: rgba(255, 255, 255, 0.08);
}

.room-icon,
.tunnel-icon {
  font-size: 14px;
}

.room-name {
  font-size: 13px;
  color: #ccc;
}

.drawer-preview {
  flex: 1;
}

.drawer-meta {
  display: flex;
  gap: 6px;
}

.drawer-wing,
.drawer-room {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 3px;
  background: rgba(108, 99, 255, 0.15);
  color: #b8b0ff;
}

.drawer-modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.drawer-modal {
  background: #1e1e2e;
  border-radius: 12px;
  width: 500px;
  max-height: 70vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.drawer-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  color: #eee;
  font-weight: 600;
  font-size: 14px;
}

.close-modal-btn {
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: #eee;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  cursor: pointer;
}

.drawer-modal-body {
  flex: 1;
  padding: 16px 18px;
  overflow-y: auto;
}

.drawer-content {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 13px;
  color: #ddd;
  line-height: 1.5;
  margin: 0;
}

.drawer-modal-footer {
  padding: 10px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 12px;
  color: #666;
}
</style>
