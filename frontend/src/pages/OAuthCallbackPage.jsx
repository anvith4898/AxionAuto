import React, { useEffect } from 'react';

export default function OAuthCallbackPage() {
  const params = new URLSearchParams(window.location.search);
  const status = params.get('status') || 'error';
  const message = params.get('message') || (status === 'success'
    ? 'You can close this window.'
    : 'The Instagram connection could not be completed.');

  useEffect(() => {
    const payload = {
      status,
      accountId: params.get('accountId'),
      username: params.get('username'),
      message,
    };

    try {
      if (window.opener && !window.opener.closed) {
        window.opener.postMessage({ type: 'axion-instagram-oauth', payload }, window.location.origin);
      }
    } catch {
      // ignore cross-window messaging issues
    }

    const timer = setTimeout(() => window.close(), 300);
    return () => clearTimeout(timer);
  }, []);

  return (
    <div style={{
      minHeight: '100vh',
      display: 'grid',
      placeItems: 'center',
      background: 'linear-gradient(135deg, #111827, #1f2937)',
      color: '#fff',
      textAlign: 'center',
      padding: 24,
    }}>
      <div>
        <h1 style={{ marginBottom: 8 }}>
          {status === 'success' ? 'Instagram connected' : 'Instagram connection failed'}
        </h1>
        <p style={{ opacity: 0.8 }}>{message}</p>
      </div>
    </div>
  );
}
