<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <img src="../assets/logo.svg" alt="Axolotl" class="login-logo" />
        <h1>Axolotl</h1>
        <p>Visual AI Agent Builder</p>
      </div>

      <div v-if="error" class="login-error">{{ error }}</div>

      <div class="login-form">
        <div class="field">
          <label>Username</label>
          <input v-model="username" type="text" placeholder="Enter username" @keyup.enter="login" autofocus />
        </div>
        <div class="field">
          <label>Password</label>
          <input v-model="password" type="password" placeholder="Enter password" @keyup.enter="login" />
        </div>
        <button class="login-btn" @click="login" :disabled="loading">
          {{ loading ? 'Signing in...' : 'Sign in' }}
        </button>
      </div>

      <div v-if="showHint" class="login-hint">
        Default: admin / admin
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../stores/authStore';

const router = useRouter();
const authStore = useAuthStore();
const username = ref('');
const password = ref('');
const error = ref('');
const loading = ref(false);
const showHint = ref(false);

async function login() {
  error.value = '';
  loading.value = true;
  try {
    await authStore.login(username.value, password.value);
    router.push('/');
  } catch (e: any) {
    error.value = e.response?.data?.error || e.message || 'Login error';
    showHint.value = true;
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: var(--bg-primary);
}
.login-card {
  background: var(--bg-card);
  border-radius: var(--radius-lg);
  padding: var(--space-8);
  width: 380px;
  box-shadow: var(--shadow-lg);
  border: 1px solid var(--border-subtle);
}
.login-header {
  text-align: center;
  margin-bottom: var(--space-6);
}
.login-logo {
  width: 64px;
  height: 64px;
}
.login-header h1 {
  font-size: var(--text-2xl);
  color: var(--text-primary);
  margin: var(--space-2) 0 var(--space-1);
}
.login-header p {
  color: var(--text-secondary);
  font-size: var(--text-sm);
}
.login-error {
  background: var(--error-light);
  color: var(--error);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  margin-bottom: var(--space-4);
}
.login-form { display: flex; flex-direction: column; gap: var(--space-4); }
.field { display: flex; flex-direction: column; gap: var(--space-1); }
.field label {
  font-size: var(--text-xs);
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.field input {
  background: var(--bg-input);
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: var(--space-3) var(--space-4);
  font-size: var(--text-sm);
  outline: none;
}
.field input:focus {
  border-color: var(--border-focus);
}
.login-btn {
  background: var(--accent-gradient);
  border: none;
  color: white;
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  margin-top: var(--space-1);
}
.login-btn:hover { opacity: 0.9; }
.login-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.login-hint {
  text-align: center;
  margin-top: var(--space-5);
  font-size: var(--text-xs);
  color: var(--text-muted);
}
</style>
