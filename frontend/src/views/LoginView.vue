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
  background: #13131f;
}
.login-card {
  background: #1e1e2e;
  border-radius: 16px;
  padding: 40px;
  width: 380px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.5);
  border: 1px solid rgba(255,255,255,0.08);
}
.login-header {
  text-align: center;
  margin-bottom: 30px;
}
.login-logo {
  width: 64px;
  height: 64px;
}
.login-header h1 {
  font-size: 28px;
  color: #eee;
  margin: 8px 0 4px;
}
.login-header p {
  color: #888;
  font-size: 14px;
}
.login-error {
  background: rgba(244, 67, 54, 0.15);
  color: #ff6b6b;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 16px;
}
.login-form { display: flex; flex-direction: column; gap: 16px; }
.field { display: flex; flex-direction: column; gap: 4px; }
.field label {
  font-size: 12px;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.field input {
  background: #13131f;
  border: 1px solid #4a4a6a;
  color: #eee;
  border-radius: 8px;
  padding: 12px 14px;
  font-size: 14px;
  outline: none;
}
.field input:focus {
  border-color: #7b5cff;
}
.login-btn {
  background: linear-gradient(135deg, #7b5cff, #4facfe);
  border: none;
  color: white;
  padding: 12px;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 4px;
}
.login-btn:hover { opacity: 0.9; }
.login-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.login-hint {
  text-align: center;
  margin-top: 20px;
  font-size: 12px;
  color: #666;
}
</style>
