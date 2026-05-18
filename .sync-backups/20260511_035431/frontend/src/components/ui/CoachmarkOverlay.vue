<template>
  <Transition name="coachmark-fade">
    <div v-if="visible" class="coachmark-overlay" @click.self="skip">
      <!-- Step spotlight -->
      <div class="coachmark-spotlight" :style="spotlightStyle" />

      <!-- Tooltip card -->
      <div class="coachmark-card" :class="steps[currentStep].position" :style="cardStyle">
        <div class="coachmark-card__arrow" :class="steps[currentStep].position" />
        <div class="coachmark-card__step">{{ currentStep + 1 }} / {{ steps.length }}</div>
        <h3 class="coachmark-card__title">{{ steps[currentStep].title }}</h3>
        <p class="coachmark-card__desc">{{ steps[currentStep].description }}</p>
        <div class="coachmark-card__actions">
          <button
            v-if="currentStep > 0"
            class="coachmark-btn coachmark-btn--back"
            @click="prevStep"
          >
            Back
          </button>
          <button
            v-if="currentStep < steps.length - 1"
            class="coachmark-btn coachmark-btn--primary"
            @click="nextStep"
          >
            Next
          </button>
          <button
            v-else
            class="coachmark-btn coachmark-btn--primary"
            @click="finish"
          >
            Got it!
          </button>
          <button class="coachmark-btn coachmark-btn--skip" @click="skip">
            {{ currentStep < steps.length - 1 ? 'Skip tour' : 'Dismiss' }}
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';

interface CoachmarkStep {
  title: string;
  description: string;
  position: 'top' | 'bottom' | 'left' | 'right';
  /** CSS selector for the target element */
  target: string;
}

const props = defineProps<{
  visible: boolean;
}>();

const emit = defineEmits<{
  done: [];
  skip: [];
}>();

const currentStep = ref(0);

const steps: CoachmarkStep[] = [
  {
    title: 'Workflow Canvas',
    description: 'This is your visual canvas. Drag Source, Agent, and Output nodes from the toolbar to build AI pipelines. Connect them to define the flow.',
    position: 'bottom',
    target: '.vue-flow__canvas',
  },
  {
    title: 'Node Toolbar',
    description: 'Click "＋ Add" to open the node menu. You can also group nodes, undo/redo changes, and access panels for memory, templates, and execution history.',
    position: 'bottom',
    target: '.toolbar-panel',
  },
  {
    title: 'Run Your Workflow',
    description: 'Hit the Run button to execute your workflow. Watch real-time progress in the right panel. Switch between Execute, Analyze, and Dry Run modes.',
    position: 'left',
    target: '.run-schema-btn',
  },
  {
    title: 'Right Panel',
    description: 'Access execution history, memory graph, templates, and the plan/todo list from the right panel. Toggle panels with the toolbar buttons.',
    position: 'left',
    target: '.right-panel',
  },
];

const targetRect = computed(() => {
  const step = steps[currentStep.value];
  if (!step) return null;
  const el = document.querySelector(step.target);
  if (!el) return null;
  return el.getBoundingClientRect();
});

const spotlightStyle = computed(() => {
  const rect = targetRect.value;
  if (!rect) return { display: 'none' };
  return {
    position: 'fixed' as const,
    left: `${rect.left - 6}px`,
    top: `${rect.top - 6}px`,
    width: `${rect.width + 12}px`,
    height: `${rect.height + 12}px`,
    borderRadius: '8px',
    boxShadow: '0 0 0 4px rgba(108, 99, 255, 0.5), 0 0 0 9999px rgba(0, 0, 0, 0.45)',
    pointerEvents: 'none',
    transition: 'all 0.3s ease',
    zIndex: 3001,
  };
});

const cardStyle = computed(() => {
  const rect = targetRect.value;
  if (!rect) return {};
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const step = steps[currentStep.value];

  switch (step.position) {
    case 'bottom':
      return {
        position: 'fixed' as const,
        left: `${Math.min(Math.max(rect.left + rect.width / 2 - 180, 16), vw - 376)}px`,
        top: `${rect.bottom + 16}px`,
      };
    case 'top':
      return {
        position: 'fixed' as const,
        left: `${Math.min(Math.max(rect.left + rect.width / 2 - 180, 16), vw - 376)}px`,
        top: `${rect.top - 16}px`,
      };
    case 'left':
      return {
        position: 'fixed' as const,
        left: `${rect.left - 8}px`,
        top: `${Math.min(Math.max(rect.top + rect.height / 2 - 80, 16), vh - 220)}px`,
      };
    case 'right':
      return {
        position: 'fixed' as const,
        left: `${rect.right + 8}px`,
        top: `${Math.min(Math.max(rect.top + rect.height / 2 - 80, 16), vh - 220)}px`,
      };
    default:
      return {};
  }
});

function nextStep() {
  if (currentStep.value < steps.length - 1) {
    currentStep.value++;
  }
}

function prevStep() {
  if (currentStep.value > 0) {
    currentStep.value--;
  }
}

function finish() {
  localStorage.setItem('axolotl:coachmarks', 'done');
  emit('done');
}

function skip() {
  localStorage.setItem('axolotl:coachmarks', 'skipped');
  emit('skip');
}

let resizeHandler: (() => void) | null = null;

onMounted(() => {
  resizeHandler = () => {
    // Force reactivity update by triggering computed recalculation
    currentStep.value = currentStep.value;
  };
  window.addEventListener('resize', resizeHandler);
});

onUnmounted(() => {
  if (resizeHandler) {
    window.removeEventListener('resize', resizeHandler);
  }
});
</script>

<style scoped>
.coachmark-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.coachmark-spotlight {
  pointer-events: none;
}

.coachmark-card {
  position: fixed;
  width: 360px;
  background: #1e1e2e;
  border: 1px solid rgba(108, 99, 255, 0.3);
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
  z-index: 3002;
}

.coachmark-card__arrow {
  position: absolute;
  width: 12px;
  height: 12px;
  background: #1e1e2e;
  border-left: 1px solid rgba(108, 99, 255, 0.3);
  border-top: 1px solid rgba(108, 99, 255, 0.3);
}

.coachmark-card__arrow.bottom {
  top: -7px;
  left: 50%;
  margin-left: -6px;
  transform: rotate(45deg);
}

.coachmark-card__arrow.top {
  bottom: -7px;
  left: 50%;
  margin-left: -6px;
  transform: rotate(225deg);
}

.coachmark-card__arrow.left {
  right: -7px;
  top: 50%;
  margin-top: -6px;
  transform: rotate(135deg);
}

.coachmark-card__arrow.right {
  left: -7px;
  top: 50%;
  margin-top: -6px;
  transform: rotate(315deg);
}

.coachmark-card__step {
  font-size: 11px;
  font-weight: 600;
  color: #6c63ff;
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 8px;
}

.coachmark-card__title {
  font-size: 16px;
  font-weight: 700;
  color: #e0e0e0;
  margin: 0 0 8px;
}

.coachmark-card__desc {
  font-size: 13px;
  color: #aaa;
  line-height: 1.5;
  margin: 0 0 16px;
}

.coachmark-card__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.coachmark-btn {
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  transition: all 0.15s;
  font-family: inherit;
}

.coachmark-btn--primary {
  background: #6c63ff;
  color: white;
  flex: 1;
}

.coachmark-btn--primary:hover {
  background: #5a52e0;
}

.coachmark-btn--back {
  background: transparent;
  color: #888;
  border: 1px solid #4a4a6a;
}

.coachmark-btn--back:hover {
  background: rgba(255, 255, 255, 0.05);
}

.coachmark-btn--skip {
  background: transparent;
  color: #666;
  flex: 1;
}

.coachmark-btn--skip:hover {
  color: #888;
}

/* Transition */
.coachmark-fade-enter-active,
.coachmark-fade-leave-active {
  transition: opacity 0.3s ease;
}
.coachmark-fade-enter-from,
.coachmark-fade-leave-to {
  opacity: 0;
}
</style>
