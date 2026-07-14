import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8082/api';

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('axolotl_token'));
  const refreshToken = ref<string | null>(localStorage.getItem('axolotl_refresh_token'));
  const username = ref<string | null>(localStorage.getItem('axolotl_username'));
  const role = ref<string | null>(localStorage.getItem('axolotl_role'));

  const isAuthenticated = computed(() => !!token.value);
  const isAdmin = computed(() => role.value === 'admin');

  function setAuth(data: { token: string; username: string; role: string; refreshToken?: string }) {
    token.value = data.token;
    username.value = data.username;
    role.value = data.role;
    localStorage.setItem('axolotl_token', data.token);
    localStorage.setItem('axolotl_username', data.username);
    localStorage.setItem('axolotl_role', data.role);
    if (data.refreshToken) {
      refreshToken.value = data.refreshToken;
      localStorage.setItem('axolotl_refresh_token', data.refreshToken);
    }
  }

  function logout() {
    token.value = null;
    refreshToken.value = null;
    username.value = null;
    role.value = null;
    localStorage.removeItem('axolotl_token');
    localStorage.removeItem('axolotl_refresh_token');
    localStorage.removeItem('axolotl_username');
    localStorage.removeItem('axolotl_role');
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
    token, username, role, refreshToken,
    isAuthenticated, isAdmin,
    login, register, logout, getAuthHeaders,
  };
});
