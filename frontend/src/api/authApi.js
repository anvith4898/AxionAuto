import client from './client';

export async function login(email, password) {
  const { data } = await client.post('/api/v1/auth/login', { email, password });
  return data;
}

export async function getCurrentSession() {
  const { data } = await client.get('/api/v1/auth/me');
  return data;
}

export async function logout() {
  await client.post('/api/v1/auth/logout');
}
