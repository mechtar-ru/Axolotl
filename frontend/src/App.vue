<template>
  <a href="#main-content" class="skip-nav">Skip to main content</a>
  <router-view />
  <ToastContainer />
  <OnboardingWalkthrough
    v-if="showOnboarding"
    @close="dismissOnboarding"
    @try-source="handleTrySource"
    @try-agent="handleTryAgent"
    @try-execute="handleTryExecute"
    @try-templates="handleTryTemplates"
  />
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useSchemaStore } from './stores/schemaStore';
import ToastContainer from './components/ToastContainer.vue';
import OnboardingWalkthrough from './components/onboarding/OnboardingWalkthrough.vue';

const schemaStore = useSchemaStore();
const showOnboarding = ref(false);

onMounted(() => {
  schemaStore.loadSchemas();

  // Show onboarding for first-time users
  try {
    const dismissed = localStorage.getItem('onboardingDismissed');
    const schemaCount = schemaStore.schemas.length;
    if (!dismissed && schemaCount === 0) {
      showOnboarding.value = true;
    }
  } catch {}
});

function dismissOnboarding() {
  showOnboarding.value = false;
  try {
    localStorage.setItem('onboardingDismissed', 'true');
  } catch {}
}

function handleTrySource() {
  dismissOnboarding();
  // Could navigate to create new schema with a source node
}

function handleTryAgent() {
  dismissOnboarding();
}

function handleTryExecute() {
  dismissOnboarding();
}

function handleTryTemplates() {
  dismissOnboarding();
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: var(--font-sans);
  background: var(--bg-primary);
  color: var(--text-primary);
}

/* Skip navigation for accessibility */
.skip-nav {
  position: absolute;
  top: -40px;
  left: 10px;
  background: var(--accent);
  color: var(--text-inverse);
  padding: 8px 16px;
  border-radius: var(--radius-sm);
  z-index: var(--z-tooltip);
  transition: top var(--transition);
}

.skip-nav:focus {
  top: 10px;
}

.vue-flow__node {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
}
</style>
