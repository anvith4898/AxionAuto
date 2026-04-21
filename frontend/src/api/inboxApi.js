import client from './client';

export async function getThreads() {
  const { data } = await client.get('/api/v1/inbox/threads');
  return data;
}

export async function getMessages(threadId) {
  const { data } = await client.get(`/api/v1/inbox/threads/${threadId}`);
  return data;
}

export async function sendMessage(threadId, text) {
  const { data } = await client.post(`/api/v1/inbox/threads/${threadId}/messages`, { text });
  return data;
}
