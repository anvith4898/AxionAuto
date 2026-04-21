import React, { useEffect, useRef, useState } from 'react';
import { Send, MessageSquare } from 'lucide-react';
import { AppLayout } from '../components/layout';
import { LoadingCenter, ErrorBox, Spinner } from '../components/ui';
import { useInbox } from '../hooks/useInbox';

function formatTime(isoString) {
  const d = new Date(isoString);
  const now = new Date();
  const diffMs = now - d;
  const diffMins = Math.floor(diffMs / 60000);
  if (diffMins < 1)   return 'just now';
  if (diffMins < 60)  return `${diffMins}m ago`;
  const diffHrs = Math.floor(diffMins / 60);
  if (diffHrs < 24)   return `${diffHrs}h ago`;
  return d.toLocaleDateString();
}

function formatMessageTime(isoString) {
  return new Date(isoString).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export default function InboxPage() {
  const {
    threads, messages, selectedThread,
    loadingThreads, loadingMessages, error,
    selectThread, sendMessage,
  } = useInbox();

  const [reply, setReply] = useState('');
  const [sending, setSending] = useState(false);
  const messagesEndRef = useRef(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSend = async (e) => {
    e.preventDefault();
    if (!reply.trim() || sending) return;
    setSending(true);
    await sendMessage(reply.trim());
    setReply('');
    setSending(false);
  };

  return (
    <AppLayout title="Inbox" topBarRight={
      <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
        {threads.filter((t) => t.unread).length} unread
      </span>
    }>
      <div className="inbox-layout" style={{ height: 'calc(100vh - var(--topbar-h) - 56px)', margin: '-28px' }}>
        {/* Thread List */}
        <div className="thread-list">
          <div className="thread-list-header">
            <h3 style={{ fontSize: 14 }}>Conversations</h3>
            <span className="badge badge-accent">{threads.length}</span>
          </div>

          {error && <div style={{ padding: 12 }}><ErrorBox message={error} /></div>}

          <div className="thread-items">
            {loadingThreads
              ? <LoadingCenter label="Loading threads..." />
              : threads.map((thread) => (
                <div
                  key={thread.id}
                  id={`thread-${thread.id}`}
                  className={`thread-item ${selectedThread?.id === thread.id ? 'active' : ''}`}
                  onClick={() => selectThread(thread)}
                >
                  <div className="thread-avatar">
                    {thread.senderName[0].toUpperCase()}
                  </div>
                  <div className="thread-info">
                    <div className="thread-name">{thread.senderName}</div>
                    <div className="thread-preview">{thread.preview}</div>
                  </div>
                  <div className="thread-meta">
                    <div className="thread-time">{formatTime(thread.updatedAt)}</div>
                    {thread.unread && <div className="unread-dot" />}
                  </div>
                </div>
              ))
            }
          </div>
        </div>

        {/* Message Area */}
        <div className="message-area">
          {!selectedThread ? (
            <div className="empty-state">
              <div className="empty-state-icon">
                <MessageSquare size={28} color="var(--text-muted)" />
              </div>
              <h3 style={{ color: 'var(--text-secondary)' }}>Select a conversation</h3>
              <p style={{ fontSize: 13 }}>Choose a thread from the left to view messages.</p>
            </div>
          ) : (
            <>
              {/* Header */}
              <div className="message-area-header">
                <div className="thread-avatar" style={{ width: 36, height: 36, fontSize: 14 }}>
                  {selectedThread.senderName[0].toUpperCase()}
                </div>
                <div>
                  <div style={{ fontWeight: 600, fontSize: 14 }}>{selectedThread.senderName}</div>
                  <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>ID: {selectedThread.senderId}</div>
                </div>
              </div>

              {/* Messages */}
              <div className="messages-list">
                {loadingMessages
                  ? <LoadingCenter label="Loading messages..." />
                  : messages.length === 0
                    ? <div className="empty-state" style={{ height: 'auto', padding: 40 }}>
                        <p>No messages yet.</p>
                      </div>
                    : messages.map((msg) => (
                        <div
                          key={msg.id}
                          className={`message-bubble-wrap ${msg.direction}`}
                        >
                          <div className={`message-bubble ${msg.direction}`}>
                            {msg.text}
                            <div className="message-time">{formatMessageTime(msg.timestamp)}</div>
                          </div>
                        </div>
                      ))
                }
                <div ref={messagesEndRef} />
              </div>

              {/* Reply input */}
              <form
                onSubmit={handleSend}
                style={{
                  padding: '12px 16px',
                  borderTop: '1px solid var(--border)',
                  display: 'flex',
                  gap: 10,
                  background: 'var(--bg-glass)',
                  backdropFilter: 'blur(16px)',
                }}
              >
                <input
                  id="reply-input"
                  type="text"
                  className="input"
                  placeholder="Type a reply..."
                  value={reply}
                  onChange={(e) => setReply(e.target.value)}
                  style={{ flex: 1 }}
                  autoComplete="off"
                />
                <button
                  id="send-message-btn"
                  type="submit"
                  className="btn btn-primary"
                  disabled={!reply.trim() || sending}
                  style={{ padding: '10px 16px' }}
                >
                  {sending ? <Spinner /> : <Send size={16} />}
                </button>
              </form>
            </>
          )}
        </div>
      </div>
    </AppLayout>
  );
}
