import React from 'react';
import { CheckCircle, XCircle, ExternalLink } from 'lucide-react';

function InstagramIcon({ size = 24, color = 'currentColor' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="2" y="2" width="20" height="20" rx="5" ry="5"/>
      <circle cx="12" cy="12" r="4"/>
      <circle cx="17.5" cy="6.5" r="0.5" fill={color} stroke="none"/>
    </svg>
  );
}
import { AppLayout } from '../components/layout';
import { Badge, Spinner, ErrorBox } from '../components/ui';
import { useInstagram } from '../hooks/useInstagram';

export default function ConnectPage() {
  const { igStatus, loading, connecting, error, connect, disconnect } = useInstagram();

  return (
    <AppLayout title="Connect Accounts">
      <div className="page-header">
        <div className="page-header-left">
          <h1>Connect Instagram</h1>
          <p className="page-header-sub">Link your Instagram Business account to enable automation.</p>
        </div>
      </div>

      {error && <div style={{ marginBottom: 16 }}><ErrorBox message={error} /></div>}

      <div className="connect-grid">
        {/* Instagram Card */}
        <div className="connect-card">
          <div className="connect-card-icon">
            <InstagramIcon size={26} color="#fff" />
          </div>

          <div>
            <h3>Instagram Business</h3>
            <p style={{ fontSize: 13, marginTop: 4 }}>
              Connect your Instagram Business or Creator account to receive DMs and comments in your inbox.
            </p>
          </div>

          <div style={{ width: '100%' }}>
            {loading ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-muted)', fontSize: 13 }}>
                <Spinner /> Checking status...
              </div>
            ) : igStatus.connected ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <CheckCircle size={16} color="var(--green)" />
                  <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--green)' }}>Connected</span>
                </div>
                {igStatus.igUsername && (
                  <div style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)', padding: '10px 14px' }}>
                    <div style={{ fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.4px', marginBottom: 4 }}>Account</div>
                    <div style={{ fontWeight: 600 }}>{igStatus.igUsername}</div>
                    {igStatus.accountId && <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 2 }}>ID: {igStatus.accountId}</div>}
                  </div>
                )}
                <button id="disconnect-ig-btn" className="btn btn-danger btn-sm" onClick={disconnect} disabled={loading}>
                  <XCircle size={14} /> Disconnect
                </button>
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <XCircle size={16} color="var(--text-muted)" />
                  <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>Not connected</span>
                </div>
                <button
                  id="connect-ig-btn"
                  className="btn btn-primary"
                  onClick={connect}
                  disabled={connecting}
                >
                  {connecting
                    ? <><Spinner /> Connecting...</>
                    : <><InstagramIcon size={15} /> Connect Instagram</>
                  }
                </button>
              </div>
            )}
          </div>

          {/* Requirements */}
          <div style={{ width: '100%', marginTop: 4 }}>
            <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.4px' }}>Requirements</div>
            {[
              'Instagram Business or Creator account',
              'Connected Facebook Page',
              'Messaging access enabled',
            ].map((req) => (
              <div key={req} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                <CheckCircle size={13} color="var(--text-muted)" />
                <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{req}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Info card */}
        <div className="card" style={{ alignSelf: 'start' }}>
          <h3 style={{ marginBottom: 12 }}>How it works</h3>
          {[
            { step: '1', title: 'Click Connect',         desc: "You'll be redirected to Meta's OAuth page." },
            { step: '2', title: 'Authorize Permissions',  desc: 'Grant AxionAuto access to your messages.' },
            { step: '3', title: 'Automation starts',      desc: 'Rules will fire automatically on new DMs.' },
          ].map((item) => (
            <div key={item.step} style={{ display: 'flex', gap: 14, marginBottom: 18 }}>
              <div style={{
                width: 28, height: 28, borderRadius: '50%',
                background: 'var(--accent-dim)', border: '1px solid var(--accent-border)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 12, fontWeight: 700, color: 'var(--accent-light)', flexShrink: 0,
              }}>
                {item.step}
              </div>
              <div>
                <div style={{ fontSize: 13, fontWeight: 600. }}>{item.title}</div>
                <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>{item.desc}</div>
              </div>
            </div>
          ))}

          <a
            href="https://developers.facebook.com/docs/instagram-api/"
            target="_blank"
            rel="noreferrer"
            className="btn btn-ghost btn-sm"
            style={{ width: '100%', justifyContent: 'center', marginTop: 8 }}
          >
            <ExternalLink size={13} /> Meta API Documentation
          </a>
        </div>
      </div>
    </AppLayout>
  );
}
