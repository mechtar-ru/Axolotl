import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8082/api';

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(sessionStorage.getItem('axolotl_token'));
  const username = ref<string | null>(sessionStorage.getItem('axolotl_username'));
  const role = ref<string | null>(sessionStorage.getItem('axolotl_role'));

  const isAuthenticated = computed(() => !!token.value);
  const isAdmin = computed(() => role.value === 'admin');

  function setAuth(data: { token: string; username: string; role: string }) {
    token.value = data.token;
    username.value = data.username;
    role.value = data.role;
    sessionStorage.setItem('axolotl_token', data.token);
    sessionStorage.setItem('axolotl_username', data.username);
    sessionStorage.setItem('axolotl_role', data.role);
  }

  function logout() {
    token.value = null;
    username.value = null;
    role.value = null;
    sessionStorage.removeItem('axolotl_token');
    sessionStorage.removeItem('axolotl_username');
    sessionStorage.removeItem('axolotl_role');
  }

  async function login(user: string, password: string) {
    const response = await axios.post(`${API_BASE_URL}/auth/login`, {
      username: user,
      password,
    });
    if (response.data.error) throw new Error(response.data.error);
    setAuth(response.data);
    return response.data;
  }

  async function register(user: string, password: string) {
    const response = await axios.post(`${API_BASE_URL}/auth/register`, {
      username: user,
      password,
    });
    if (response.data.error) throw new Error(response.data.error);
    setAuth(response.data);
    return response.data;
  }

  function getAuthHeaders() {
    return token.value ? { Authorization: `Bearer ${token.value}` } : {};
  }

  return {
    token, username, role,
    isAuthenticated, isAdmin,
    login, register, logout, getAuthHeaders,
  };
});
