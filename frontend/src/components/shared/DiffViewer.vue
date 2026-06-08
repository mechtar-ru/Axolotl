<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  oldText: string
  newText: string
  label?: string
  maxHeight?: string
}>()

interface DiffLine {
  type: 'unchanged' | 'added' | 'removed'
  text: string
  oldLineNum?: number
  newLineNum?: number
}

const lines = computed(() => {
  const oldLines = (props.oldText || '').split('\n')
  const newLines = (props.newText || '').split('\n')
  const result: DiffLine[] = []

  // Simple LCS-based diff
  const oldLen = oldLines.length
  const newLen = newLines.length
  // Use flat array for better TypeScript inference: dp[i * (newLen + 1) + j]
  const width = newLen + 1
  const dp: number[] = new Array((oldLen + 1) * width).fill(0)
  const idx = (i: number, j: number) => i * width + j
  const get = (i: number, j: number) => dp[idx(i, j)]!

  for (let i = 1; i <= oldLen; i++) {
    for (let j = 1; j <= newLen; j++) {
      // i>=1, j>=1 guarantees in-bounds access
      const oldLine = oldLines[i - 1]!
      const newLine = newLines[j - 1]!
      dp[idx(i, j)] = oldLine === newLine
        ? get(i - 1, j - 1) + 1
        : Math.max(get(i - 1, j), get(i, j - 1))
    }
  }

  // Backtrack
  let i = oldLen, j = newLen
  const temp: DiffLine[] = []
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0) {
      const oldLine = oldLines[i - 1]!
      const newLine = newLines[j - 1]!
      if (oldLine === newLine) {
        temp.push({ type: 'unchanged', text: oldLine, oldLineNum: i, newLineNum: j })
        i--; j--
      } else if (j > 0 && (i === 0 || get(i, j - 1) >= get(i - 1, j))) {
        temp.push({ type: 'added', text: newLine, newLineNum: j })
        j--
      } else {
        temp.push({ type: 'removed', text: oldLine, oldLineNum: i })
        i--
      }
    } else if (j > 0) {
      const newLine = newLines[j - 1]!
      temp.push({ type: 'added', text: newLine, newLineNum: j })
      j--
    } else {
      const oldLine = oldLines[i - 1]!
      temp.push({ type: 'removed', text: oldLine, oldLineNum: i })
      i--
    }
  }

  result.push(...temp.reverse())
  return result
})

const stats = computed(() => {
  const added = lines.value.filter(l => l.type === 'added').length
  const removed = lines.value.filter(l => l.type === 'removed').length
  return { added, removed }
})
</script>

<template>
  <div class="diff-viewer">
    <div class="diff-header">
      <span class="diff-label">{{ label || 'Diff' }}</span>
      <span class="diff-stats">
        <span class="stat-added">+{{ stats.added }}</span>
        <span class="stat-removed">-{{ stats.removed }}</span>
      </span>
    </div>
    <div class="diff-lines" :style="{ maxHeight: maxHeight || '300px' }">
      <div
        v-for="(line, idx) in lines"
        :key="idx"
        :class="['diff-line', 'line-' + line.type]"
      >
        <span class="line-num old-num">{{ line.oldLineNum || '' }}</span>
        <span class="line-num new-num">{{ line.newLineNum || '' }}</span>
        <span class="line-prefix">{{ line.type === 'added' ? '+' : line.type === 'removed' ? '-' : ' ' }}</span>
        <span class="line-text">{{ line.text }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.diff-viewer {
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--bg-primary);
}

.diff-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-2) var(--space-3);
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
}

.diff-label {
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
}

.diff-stats {
  display: flex;
  gap: var(--space-2);
  font-size: var(--text-xs);
  font-weight: 600;
}

.stat-added { color: var(--success); }
.stat-removed { color: var(--error); }

.diff-lines {
  overflow-y: auto;
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  line-height: 1.5;
}

.diff-line {
  display: flex;
  padding: 1px var(--space-2);
  min-height: 20px;
}

.line-added { background: color-mix(in srgb, var(--success) 12%, transparent); }
.line-removed { background: color-mix(in srgb, var(--error) 12%, transparent); }
.line-unchanged { background: transparent; }

.line-num {
  min-width: 32px;
  text-align: right;
  padding-right: var(--space-1);
  color: var(--text-muted);
  user-select: none;
}

.line-prefix {
  min-width: 14px;
  color: var(--text-muted);
  user-select: none;
  font-weight: 700;
}

.line-added .line-prefix { color: var(--success); }
.line-removed .line-prefix { color: var(--error); }

.line-text {
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-primary);
}

.line-added .line-text { color: var(--success); }
.line-removed .line-text { color: var(--error); }
</style>
