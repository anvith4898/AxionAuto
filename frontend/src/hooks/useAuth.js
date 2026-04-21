import { useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { login as apiLogin, logout as apiLogout } from '../api/authApi';
import { useApp } from '../context/AppContext';

export function useAuth() {
  const { user, setUser, clearUser, toast } = useApp();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState(null);

  const login = useCallback(async (email, password) => {
    setLoading(true);
    setError(null);
    try {
      const session = await apiLogin(email, password);
      setUser(session);
      toast('Welcome back, ' + (session.name || session.email) + '!', 'success');
      navigate('/');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [setUser, toast, navigate]);

  const logout = useCallback(async () => {
    try { await apiLogout(); } catch { /* ignore */ }
    clearUser();
    navigate('/login');
  }, [clearUser, navigate]);

  return { user, loading, error, login, logout, isAuthenticated: !!user };
}
