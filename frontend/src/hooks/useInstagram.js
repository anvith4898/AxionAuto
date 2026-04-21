import { useState, useCallback, useEffect } from 'react';
import { getInstagramStatus, initiateInstagramLogin, disconnectInstagram } from '../api/instagramApi';
import { useApp } from '../context/AppContext';

export function useInstagram() {
  const { igStatus, setIgStatus, toast } = useApp();
  const [loading, setLoading] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState(null);

  const fetchStatus = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const status = await getInstagramStatus();
      setIgStatus(status);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [setIgStatus]);

  useEffect(() => {
    fetchStatus();
  }, [fetchStatus]);

  const connect = useCallback(async () => {
    setConnecting(true);
    setError(null);
    try {
      const { authorizationUrl } = await initiateInstagramLogin(`${window.location.origin}/oauth/callback`);
      const popup = window.open(authorizationUrl, 'ig-oauth', 'width=600,height=700');

      const handleMessage = (event) => {
        if (event.origin !== window.location.origin) return;
        if (event.data?.type !== 'axion-instagram-oauth') return;
        window.removeEventListener('message', handleMessage);
        setConnecting(false);
        if (event.data?.payload?.status === 'success') {
          setTimeout(fetchStatus, 300);
          toast('Instagram account connected successfully!', 'success');
          return;
        }

        const errorMessage = event.data?.payload?.message || 'Instagram connection was not completed.';
        setError(errorMessage);
        toast(errorMessage, 'error');
      };

      window.addEventListener('message', handleMessage);

      const poll = setInterval(() => {
        if (popup?.closed) {
          clearInterval(poll);
          window.removeEventListener('message', handleMessage);
          setConnecting(false);
          setTimeout(fetchStatus, 300);
        }
      }, 500);
    } catch (err) {
      setConnecting(false);
      setError(err.message || 'Failed to start Instagram OAuth');
    }
  }, [fetchStatus, toast]);

  const disconnect = useCallback(async () => {
    setLoading(true);
    try {
      await disconnectInstagram();
      setIgStatus({ connected: false, accountId: null, igUsername: null });
      toast('Instagram account disconnected.', 'info');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [setIgStatus, toast]);

  return {
    igStatus,
    loading,
    connecting,
    error,
    connect,
    disconnect,
    refetch: fetchStatus,
  };
}
