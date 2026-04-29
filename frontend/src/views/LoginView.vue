<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <img src="../assets/logo.svg" alt="Axolotl" class="login-logo" />
        <h1>Axolotl</h1>
        <p>Визуальный конструктор AI-агентов</p>
      </div>

      <div v-if="error" class="login-error">{{ error }}</div>

      <div class="login-form">
        <div class="field">
          <label>Имя пользователя</label>
          <input v-model="username" type="text" placeholder="Введите логин" @keyup.enter="login" autofocus />
        </div>
        <div class="field">
          <label>Пароль</label>
          <input v-model="password" type="password" placeholder="Введите пароль" @keyup.enter="login" />
        </div>
        <button class="login-btn" @click="login" :disabled="loading">
          {{ loading ? 'Вход...' : 'Войти' }}
        </button>
      </div>

      <div v-if="showHint" class="login-hint">
        По умолчанию: admin / admin
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
    error.value = e.response?.data?.error || e.message || 'Ошибка входа';
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
  background: var(--bg-input);
}
.login-card {
  background: var(--bg-secondary);
  border-radius: var(--radius-lg);
  padding: var(--space-8);
  width: 380px;
  box-shadow: var(--shadow-lg);
  border: 1px solid var(--border-subtle);
}
.login-header {
  text-align: center;
  margin-bottom: var(--space-8);
}
.login-logo {
  width: 64px;
  height: 64px;
}
.login-header h1 {
  font-size: var(--text-3xl);
  color: var(--text-primary);
  margin: 8px 0 4px;
}
.login-header p {
  color: var(--text-muted);
  font-size: var(--text-md);
}
.login-error {
  background: var(--error-light);
  color: var(--error);
  padding: var(--space-2-5) var(--space-3-5);
  border-radius: var(--radius-sm);
  font-size: var(--text-base);
  margin-bottom: var(--space-4);
}
.login-form { display: flex; flex-direction: column; gap: var(--space-4); }
.field { display: flex; flex-direction: column; gap: var(--space-1); }
.field label {
  font-size: var(--text-sm);
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.field input {
  background: var(--bg-input);
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: var(--radius-sm);
  padding: var(--space-3) var(--space-3-5);
  font-size: var(--text-md);
  outline: none;
}
.field input:focus {
  border-color: var(--accent);
}
.login-btn {
  background: var(--accent-gradient);
  border: none;
  color: var(--text-inverse);
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--text-base);
  font-weight: 600;
  cursor: pointer;
  margin-top: var(--space-1);
}
.login-btn:hover { opacity: 0.9; }
.login-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.login-hint {
  text-align: center;
  margin-top: var(--space-5);
  font-size: var(--text-sm);
  color: var(--text-muted);
}
</style>
