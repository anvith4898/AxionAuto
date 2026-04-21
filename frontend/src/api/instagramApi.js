import client from './client';

export async function initiateInstagramLogin(redirectUrl) {
  const params = redirectUrl ? `?redirect_url=${encodeURIComponent(redirectUrl)}` : '';
  const { data } = await client.post(`/api/v1/oauth/instagram/login${params}`);
  return data;
}

export async function getInstagramStatus() {
  const { data } = await client.get('/api/v1/oauth/instagram/status');
  return data;
}

export async function disconnectInstagram() {
  await client.delete('/api/v1/oauth/instagram/disconnect');
  localStorage.removeItem('axion_ig_status');
}
