import { useState, useCallback, useEffect } from 'react';
import { getThreads, getMessages, sendMessage as apiSend } from '../api/inboxApi';
import { useApp } from '../context/AppContext';

export function useInbox() {
  const { toast } = useApp();
  const [threads,         setThreads]         = useState([]);
  const [messages,        setMessages]        = useState([]);
  const [selectedThread,  setSelectedThread]  = useState(null);
  const [loadingThreads,  setLoadingThreads]  = useState(false);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [error,           setError]           = useState(null);

  const fetchThreads = useCallback(async () => {
    setLoadingThreads(true);
    setError(null);
    try {
      const data = await getThreads();
      setThreads(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoadingThreads(false);
    }
  }, []);

  useEffect(() => { fetchThreads(); }, [fetchThreads]);

  const selectThread = useCallback(async (thread) => {
    setSelectedThread(thread);
    setLoadingMessages(true);
    setMessages([]);
    try {
      const msgs = await getMessages(thread.id);
      setMessages(msgs);
      // Mark thread as read in local state
      setThreads((prev) =>
        prev.map((t) => (t.id === thread.id ? { ...t, unread: false } : t))
      );
    } catch (err) {
      setError(err.message);
    } finally {
      setLoadingMessages(false);
    }
  }, []);

  const sendMessage = useCallback(async (text) => {
    if (!selectedThread || !text.trim()) return;
    const optimistic = {
      id: `opt-${Date.now()}`,
      text,
      direction: 'outbound',
      timestamp: new Date().toISOString(),
    };
    // Optimistic update
    setMessages((prev) => [...prev, optimistic]);
    try {
      const sent = await apiSend(selectedThread.id, text);
      setMessages((prev) => prev.map((m) => (m.id === optimistic.id ? sent : m)));
    } catch (err) {
      // Revert optimistic
      setMessages((prev) => prev.filter((m) => m.id !== optimistic.id));
      toast('Failed to send message.', 'error');
    }
  }, [selectedThread, toast]);

  return {
    threads,
    messages,
    selectedThread,
    loadingThreads,
    loadingMessages,
    error,
    selectThread,
    sendMessage,
    refetch: fetchThreads,
  };
}
