import axios from 'axios';

const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

const client = axios.create({
  baseURL: BASE_URL,
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
});

// ── Request interceptor: attach tenant/user headers from session ──────────
client.interceptors.request.use((config) => {
  const session = JSON.parse(localStorage.getItem('axion_session') || 'null');
  if (session?.token) {
    config.headers['Authorization'] = `Bearer ${session.token}`;
  }
  return config;
});

// ── Response interceptor: normalize errors ────────────────────────────────
client.interceptors.response.use(
  (res) => res,
  (err) => {
    if (!err.response) {
      const apiBase = err.config?.baseURL || BASE_URL;
      return Promise.reject(
        new Error(`API server is unreachable at ${apiBase}. Start the backend and try again.`)
      );
    }

    if (err.response?.status === 401) {
      localStorage.removeItem('axion_session');
      window.location.href = '/login';
    }
    const message =
      err.response?.data?.message ||
      err.response?.data ||
      err.message ||
      'An unexpected error occurred';
    return Promise.reject(new Error(typeof message === 'string' ? message : JSON.stringify(message)));
  }
);

export default client;
