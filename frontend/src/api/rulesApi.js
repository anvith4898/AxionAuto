import client from './client';

export async function getRules() {
  const { data } = await client.get('/api/v1/rules');
  return data;
}

export async function createRule(ruleData) {
  const { data } = await client.post('/api/v1/rules', ruleData);
  return data;
}

export async function updateRule(id, updates) {
  const { data } = await client.put(`/api/v1/rules/${id}`, updates);
  return data;
}

export async function toggleRule(id, active) {
  const { data } = await client.patch(`/api/v1/rules/${id}/toggle`, { active });
  return data;
}

export async function deleteRule(id) {
  await client.delete(`/api/v1/rules/${id}`);
}
