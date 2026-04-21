import { useState, useCallback, useEffect } from 'react';
import { getRules, createRule, updateRule, toggleRule, deleteRule } from '../api/rulesApi';
import { useApp } from '../context/AppContext';

export function useRules() {
  const { toast } = useApp();
  const [rules,   setRules]   = useState([]);
  const [loading, setLoading] = useState(false);
  const [saving,  setSaving]  = useState(false);
  const [error,   setError]   = useState(null);

  const fetchRules = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getRules();
      setRules(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchRules(); }, [fetchRules]);

  const addRule = useCallback(async (ruleData) => {
    setSaving(true);
    try {
      const newRule = await createRule(ruleData);
      setRules((prev) => [...prev, newRule]);
      toast('Rule created successfully!', 'success');
      return newRule;
    } catch (err) {
      toast('Failed to create rule: ' + err.message, 'error');
      throw err;
    } finally {
      setSaving(false);
    }
  }, [toast]);

  const editRule = useCallback(async (id, updates) => {
    setSaving(true);
    try {
      const updated = await updateRule(id, updates);
      setRules((prev) => prev.map((r) => (r.id === id ? updated : r)));
      toast('Rule updated!', 'success');
      return updated;
    } catch (err) {
      toast('Failed to update rule.', 'error');
      throw err;
    } finally {
      setSaving(false);
    }
  }, [toast]);

  const toggle = useCallback(async (id, currentActive) => {
    // Optimistic update
    setRules((prev) => prev.map((r) => (r.id === id ? { ...r, active: !currentActive } : r)));
    try {
      await toggleRule(id, !currentActive);
      toast(!currentActive ? 'Rule activated.' : 'Rule deactivated.', 'info');
    } catch (err) {
      // Revert
      setRules((prev) => prev.map((r) => (r.id === id ? { ...r, active: currentActive } : r)));
      toast('Failed to toggle rule.', 'error');
    }
  }, [toast]);

  const remove = useCallback(async (id) => {
    setRules((prev) => prev.filter((r) => r.id !== id)); // optimistic
    try {
      await deleteRule(id);
      toast('Rule deleted.', 'info');
    } catch (err) {
      await fetchRules(); // revert by refetching
      toast('Failed to delete rule.', 'error');
    }
  }, [toast, fetchRules]);

  return { rules, loading, saving, error, addRule, editRule, toggle, remove, refetch: fetchRules };
}
