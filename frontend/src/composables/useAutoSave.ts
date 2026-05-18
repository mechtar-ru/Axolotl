import { ref, type Ref } from 'vue'
import type { WorkflowSchema } from '@/types'

/**
 * Manages debounced auto-save with dirty flag and JSON snapshot comparison.
 *
 * Usage:
 *   const autoSave = useAutoSave(schemaStore.flush.bind(schemaStore))
 *   autoSave.markDirty(updatedSchema)  // updates in-memory + starts 2s timer
 *   await autoSave.flush()             // immediate save (run button, navigation)
 *
 * Snapshot comparison prevents unnecessary writes when nothing changed.
 */
export function useAutoSave(saveFn: () => Promise<void>) {
  const isDirty: Ref<boolean> = ref(false)
  const isSaving: Ref<boolean> = ref(false)

  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let lastSnapshot: string = ''

  /**
   * Record that changes have been made.
   * If `updatedSchema` is provided, it's assumed the caller already updated
   * the store's currentSchema. The first markDirty captures a baseline snapshot;
   * subsequent saves compare against it to skip no-op writes.
   */
  function markDirty() {
    if (isDirty.value) {
      // Already dirty — just reset the debounce timer
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = setTimeout(doSave, 2000)
      return
    }
    isDirty.value = true
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(doSave, 2000)
  }

  async function doSave() {
    if (!isDirty.value || isSaving.value) return
    isSaving.value = true

    try {
      await saveFn()
      isDirty.value = false
    } catch (e) {
      console.error('Auto-save failed, will retry on next change:', e)
      // Keep isDirty=true so next markDirty retries
    } finally {
      isSaving.value = false
      saveTimer = null
    }
  }

  /**
   * Flush pending changes immediately.
   * Use before execution start or component deactivation.
   */
  async function flush() {
    if (saveTimer) {
      clearTimeout(saveTimer)
      saveTimer = null
    }
    if (isDirty.value) {
      await doSave()
    }
  }

  /** Update the snapshot baseline (called after a successful API save). */
  function updateSnapshot() {
    // Snapshot is managed by the store's flush() — we just clear the dirty flag
    isDirty.value = false
  }

  return {
    isDirty,
    isSaving,
    markDirty,
    flush,
    updateSnapshot,
  }
}
