<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'

// ── Types ──
type Cell = 'wall' | 'floor' | 'goal' | 'box' | 'box_on_goal' | 'player' | 'player_on_goal'
type Level = { name: string; width: number; height: number; map: Cell[][] }

interface State {
  map: Cell[][]
  playerPos: { r: number; c: number }
  moves: number
  pushes: number
  history: { map: Cell[][]; playerPos: { r: number; c: number } }[]
}

// ── Level Data (Microban set) ──
const LEVELS: Level[] = [
  { name: 'Microban 1', width: 6, height: 6, map: parseLevel([
    '  #### ',
    '##  # ',
    '#  $  #',
    '# .  $#',
    '# .  #',
    '######'
  ])},
  { name: 'Microban 2', width: 7, height: 6, map: parseLevel([
    '  #####',
    '  #   #',
    '## # $#',
    '#  .$ #',
    '# $.#  #',
    '########'
  ])},
  { name: 'Microban 3', width: 7, height: 7, map: parseLevel([
    '   #####',
    '### #  #',
    '# $  $ #',
    '# . #  #',
    '# . $ ##',
    '#   #  #',
    '########'
  ])},
  { name: 'Microban 4', width: 8, height: 7, map: parseLevel([
    '  ######',
    '  #    #',
    '## #$$ #',
    '#  .  .#',
    '# $ . ##',
    '#   ##',
    '######'
  ])},
  { name: 'Microban 5', width: 7, height: 7, map: parseLevel([
    '########',
    '#      #',
    '#  $   #',
    '# . .$ #',
    '# $  . #',
    '#      #',
    '########'
  ])},
  { name: 'Microban 6', width: 7, height: 7, map: parseLevel([
    '  #####',
    '###   #',
    '# $ # #',
    '# . $ #',
    '# $.#  #',
    '#   ###',
    '######'
  ])},
  { name: 'Microban 7', width: 8, height: 8, map: parseLevel([
    '  ######',
    '  #    #',
    '## #$$ #',
    '#  .  .#',
    '# $ . ##',
    '#   ##',
    '######'
  ])},
  { name: 'Microban 8', width: 8, height: 8, map: parseLevel([
    '  ######',
    '## #   #',
    '#  $ $ #',
    '# .#.  #',
    '# $  $ #',
    '#  . # #',
    '##    #',
    ' ######'
  ])},
  { name: 'Microban 9', width: 9, height: 7, map: parseLevel([
    '  #######',
    '  #     #',
    '## # $  #',
    '#  $..$ #',
    '# .  #  #',
    '#      #',
    '########'
  ])},
  { name: 'Microban 10', width: 8, height: 8, map: parseLevel([
    ' ########',
    '##  #   #',
    '#  $$   #',
    '#.  . $ #',
    '# . .#  #',
    '#   $$  #',
    '#      #',
    '########'
  ])},
]

function parseLevel(rows: string[]): Cell[][] {
  return rows.map(r => [...r].map(ch => {
    switch (ch) {
      case '#': return 'wall'
      case '$': return 'box'
      case '.': return 'goal'
      case '*': return 'box_on_goal'
      case '@': return 'player'
      case '+': return 'player_on_goal'
      default:  return 'floor'
    }
  }))
}

// ── Emoji map ──
const CELL_EMOJI: Record<Cell, string> = {
  wall: '🟫',
  floor: '  ',
  goal: '⭐',
  box: '📦',
  box_on_goal: '✅',
  player: '🧑‍💼',
  player_on_goal: '🧑‍💼',
}

// ── Game State ──
const currentLevelIndex = ref(0)
const state = reactive<State>({
  map: [],
  playerPos: { r: 0, c: 0 },
  moves: 0,
  pushes: 0,
  history: [],
})

const won = computed(() => {
  for (const row of state.map) {
    for (const cell of row) {
      if (cell === 'box') return false
    }
  }
  return state.map.length > 0
})

function findPlayer(map: Cell[][]): { r: number; c: number } {
  for (let r = 0; r < map.length; r++) {
    const row = map[r]
    if (!row) continue
    for (let c = 0; c < row.length; c++) {
      const cell = row[c]
      if (cell === 'player' || cell === 'player_on_goal') return { r, c }
    }
  }
  return { r: 0, c: 0 }
}

function cloneMap(map: Cell[][]): Cell[][] {
  return map.map(r => [...r])
}

function loadLevel(index: number) {
  const level = LEVELS[index]
  const map = cloneMap(level.map)
  const playerPos = findPlayer(map)
  state.map = map
  state.playerPos = playerPos
  state.moves = 0
  state.pushes = 0
  state.history = []
}

function saveState() {
  state.history.push({
    map: cloneMap(state.map),
    playerPos: { ...state.playerPos },
  })
  if (state.history.length > 100) state.history.shift()
}

function move(dr: number, dc: number) {
  if (won.value) return

  const { r, c } = state.playerPos
  const nr = r + dr
  const nc = c + dc
  const map = state.map

  if (!map[nr] || !map[nr][nc]) return
  const target = map[nr][nc]

  if (target === 'wall') return
  if (target === 'box' || target === 'box_on_goal') {
    const nnr = nr + dr
    const nnc = nc + dc
    if (!map[nnr] || !map[nnr][nnc]) return
    const beyond = map[nnr][nnc]
    if (beyond === 'wall' || beyond === 'box' || beyond === 'box_on_goal') return

    saveState()

    // Push box
    map[nnr][nnc] = beyond === 'goal' ? 'box_on_goal' : 'box'
    map[nr][nc] = map[nr][nc] === 'box_on_goal' ? 'goal' : 'floor'
    state.pushes++
  } else {
    if (target === 'goal' || target === 'floor') {
      saveState()
    } else {
      return
    }
  }

  // Move player
  const wasOnGoal = map[r][c] === 'player_on_goal'
  map[r][c] = wasOnGoal ? 'goal' : 'floor'
  map[nr][nc] = map[nr][nc] === 'goal' || target === 'goal' ? 'player_on_goal' : 'player'

  state.playerPos = { r: nr, c: nc }
  state.moves++
}

function undo() {
  if (state.history.length === 0 || won.value) return
  const prev = state.history.pop()!
  state.map = prev.map
  state.playerPos = prev.playerPos
}

function reset() {
  loadLevel(currentLevelIndex.value)
}

function selectLevel(index: number) {
  currentLevelIndex.value = index
  loadLevel(index)
}

// ── Keyboard ──
function onKeyDown(e: KeyboardEvent) {
  switch (e.key) {
    case 'ArrowUp': case 'w': case 'W': e.preventDefault(); move(-1, 0); break
    case 'ArrowDown': case 's': case 'S': e.preventDefault(); move(1, 0); break
    case 'ArrowLeft': case 'a': case 'A': e.preventDefault(); move(0, -1); break
    case 'ArrowRight': case 'd': case 'D': e.preventDefault(); move(0, 1); break
    case 'z': case 'Z': e.preventDefault(); undo(); break
    case 'r': case 'R': e.preventDefault(); reset(); break
  }
}

onMounted(() => {
  loadLevel(0)
  window.addEventListener('keydown', onKeyDown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeyDown)
})
</script>

<template>
  <div class="sokoban">
    <!-- Header -->
    <div class="sokoban-header">
      <div class="level-select">
        <label>Level:</label>
        <select :value="currentLevelIndex" @change="selectLevel(Number(($event.target as HTMLSelectElement).value))">
          <option v-for="(lvl, i) in LEVELS" :key="i" :value="i">
            {{ lvl.name }}
          </option>
        </select>
      </div>
      <div class="stats">
        <span class="stat">Moves: <strong>{{ state.moves }}</strong></span>
        <span class="stat">Pushes: <strong>{{ state.pushes }}</strong></span>
      </div>
      <div class="controls">
        <button class="btn" @click="undo" title="Undo (Z)">↩ Undo</button>
        <button class="btn" @click="reset" title="Reset (R)">↻ Reset</button>
      </div>
    </div>

    <!-- Game Board -->
    <div class="board-wrapper">
      <div class="board" :style="{ gridTemplateColumns: `repeat(${LEVELS[currentLevelIndex].width}, 40px)` }">
        <template v-for="(row, r) in state.map" :key="r">
          <div
            v-for="(cell, c) in row"
            :key="`${r}-${c}`"
            class="cell"
            :class="{ wall: cell === 'wall', goal: cell === 'goal' || cell === 'box_on_goal' || cell === 'player_on_goal' }"
          >
            <span v-if="cell !== 'floor'" class="emoji">{{ CELL_EMOJI[cell] }}</span>
          </div>
        </template>
      </div>
    </div>

    <!-- Instructions -->
    <div class="instructions">
      Arrow Keys / WASD — Move &nbsp;|&nbsp; Z — Undo &nbsp;|&nbsp; R — Reset
    </div>

    <!-- Win Overlay -->
    <Transition name="win">
      <div v-if="won" class="win-overlay" @click="selectLevel(currentLevelIndex + 1 < LEVELS.length ? currentLevelIndex + 1 : 0)">
        <div class="win-card">
          <h2>🎉 Level Complete!</h2>
          <p>{{ state.moves }} moves, {{ state.pushes }} pushes</p>
          <button class="btn-primary" @click.stop="selectLevel(currentLevelIndex + 1 < LEVELS.length ? currentLevelIndex + 1 : 0)">
            {{ currentLevelIndex + 1 < LEVELS.length ? 'Next Level →' : '🔄 Play Again' }}
          </button>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.sokoban {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  font-family: 'Segoe UI', system-ui, sans-serif;
}

.sokoban-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1rem;
  width: 100%;
  max-width: 500px;
  justify-content: center;
}

.level-select {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.9rem;
  color: var(--text-primary);
}

.level-select select {
  padding: 0.375rem 0.625rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-primary);
  color: var(--text-primary);
  font-size: 0.85rem;
  cursor: pointer;
}

.stats {
  display: flex;
  gap: 1rem;
}

.stat {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.stat strong {
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}

.controls {
  display: flex;
  gap: 0.5rem;
}

.btn {
  padding: 0.375rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.8rem;
  cursor: pointer;
  transition: background 0.15s;
}

.btn:hover {
  background: var(--bg-hover);
}

.board-wrapper {
  background: var(--bg-secondary);
  border-radius: 12px;
  padding: 1rem;
  box-shadow: var(--shadow-md);
}

.board {
  display: grid;
  gap: 0;
}

.cell {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.5rem;
  line-height: 1;
  background: #2a2a3e;
  border-radius: 2px;
}

.cell.wall {
  background: #3d3d5c;
}

.cell.goal {
  background: #2a2a3e;
  box-shadow: inset 0 0 0 1px rgba(108, 99, 255, 0.3);
}

.emoji {
  filter: drop-shadow(0 1px 1px rgba(0, 0, 0, 0.3));
}

.instructions {
  font-size: 0.8rem;
  color: var(--text-muted);
  text-align: center;
}

.btn-primary {
  padding: 0.625rem 1.25rem;
  background: var(--accent);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s, transform 0.1s;
}

.btn-primary:hover {
  background: var(--accent-light);
  transform: translateY(-1px);
}

/* Win overlay */
.win-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.win-card {
  background: var(--bg-secondary);
  border-radius: 16px;
  padding: 2rem;
  text-align: center;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
  max-width: 340px;
}

.win-card h2 {
  font-size: 1.5rem;
  margin: 0 0 0.5rem;
  color: var(--text-primary);
}

.win-card p {
  color: var(--text-secondary);
  margin: 0 0 1.25rem;
  font-size: 0.95rem;
}

/* Transition */
.win-enter-active { transition: opacity 0.3s; }
.win-leave-active { transition: opacity 0.2s; }
.win-enter-from, .win-leave-to { opacity: 0; }
</style>
