import React from 'react';
import { Link } from 'react-router-dom';
import { MessageSquare, Zap, Link2, Activity, TrendingUp, ArrowRight } from 'lucide-react';
import { AppLayout } from '../components/layout';
import { useApp } from '../context/AppContext';
import { Toggle, Badge, Skeleton } from '../components/ui';
import { useRules } from '../hooks/useRules';
import { useInbox } from '../hooks/useInbox';
import { useInstagram } from '../hooks/useInstagram';

export default function DashboardPage() {
  const { user, automationEnabled, toggleAuto } = useApp();
  const { rules, loading: rulesLoading } = useRules();
  const { threads, loadingThreads }      = useInbox();
  const { igStatus, loading: igLoading } = useInstagram();

  const unreadCount  = threads.filter((t) => t.unread).length;
  const activeRules  = rules.filter((r) => r.active).length;

  const stats = [
    {
      label: 'Total Threads',
      value: loadingThreads ? null : threads.length,
      icon: <MessageSquare size={20} />,
      color: 'var(--accent)',
      gradient: 'linear-gradient(135deg, var(--accent), #5b21b6)',
      bg: 'var(--accent-dim)',
    },
    {
      label: 'Unread Messages',
      value: loadingThreads ? null : unreadCount,
      icon: <Activity size={20} />,
      color: 'var(--cyan)',
      gradient: 'linear-gradient(135deg, var(--cyan), #0e7490)',
      bg: 'var(--cyan-dim)',
    },
    {
      label: 'Active Rules',
      value: rulesLoading ? null : activeRules,
      icon: <Zap size={20} />,
      color: 'var(--green)',
      gradient: 'linear-gradient(135deg, var(--green), #047857)',
      bg: 'var(--green-dim)',
    },
    {
      label: 'IG Account',
      value: igLoading ? null : (igStatus.connected ? 1 : 0),
      icon: <Link2 size={20} />,
      color: 'var(--yellow)',
      gradient: 'linear-gradient(135deg, var(--yellow), #b45309)',
      bg: 'rgba(245,158,11,0.1)',
      display: igStatus.connected ? igStatus.igUsername || 'Connected' : 'Not connected',
    },
  ];

  return (
    <AppLayout title="Dashboard">
      {/* Greeting */}
      <div className="page-header">
        <div className="page-header-left">
          <h1>Good {timeOfDay()}, {user?.name?.split(' ')[0] || 'there'} 👋</h1>
          <p className="page-header-sub">Here's what's happening with your automation today.</p>
        </div>
      </div>

      {/* Automation toggle banner */}
      <div className={`automation-banner ${automationEnabled ? 'active' : ''}`}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
            <Zap size={16} color={automationEnabled ? 'var(--accent-light)' : 'var(--text-muted)'} />
            <span style={{ fontWeight: 600, fontSize: 14 }}>Global Automation</span>
            <Badge variant={automationEnabled ? 'accent' : 'muted'}>
              {automationEnabled ? 'ON' : 'OFF'}
            </Badge>
          </div>
          <p style={{ fontSize: 12, margin: 0 }}>
            {automationEnabled
              ? 'Rules are active and responding to incoming messages.'
              : 'All automation is paused. Messages will not receive auto-replies.'}
          </p>
        </div>
        <Toggle on={automationEnabled} onToggle={toggleAuto} />
      </div>

      {/* Stats grid */}
      <div className="stats-grid">
        {stats.map((stat) => (
          <div key={stat.label} className="stat-card" style={{ '--gradient': stat.gradient }}>
            <div className="stat-card-icon" style={{ background: stat.bg, color: stat.color }}>
              {stat.icon}
            </div>
            {stat.value === null
              ? <Skeleton height={32} width={60} />
              : <div className="stat-card-value">{stat.display ?? stat.value}</div>
            }
            <div className="stat-card-label">{stat.label}</div>
          </div>
        ))}
      </div>

      {/* Bottom grid */}
      <div className="dashboard-grid">
        {/* Recent threads */}
        <div className="card">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
            <h3>Recent Conversations</h3>
            <Link to="/inbox" className="btn btn-ghost btn-sm" style={{ gap: 4 }}>
              View all <ArrowRight size={13} />
            </Link>
          </div>
          {loadingThreads
            ? <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>{[...Array(3)].map((_, i) => <Skeleton key={i} height={48} />)}</div>
            : threads.slice(0, 4).map((t) => (
              <div key={t.id} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 0', borderBottom: '1px solid var(--border)' }}>
                <div className="thread-avatar" style={{ width: 36, height: 36, fontSize: 13 }}>
                  {t.senderName[0].toUpperCase()}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{t.senderName}</div>
                  <div style={{ fontSize: 12, color: 'var(--text-muted)', overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis' }}>{t.preview}</div>
                </div>
                {t.unread && <div className="unread-dot" />}
              </div>
            ))
          }
        </div>

        {/* Rules summary */}
        <div className="card">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
            <h3>Automation Rules</h3>
            <Link to="/rules" className="btn btn-ghost btn-sm" style={{ gap: 4 }}>
              Manage <ArrowRight size={13} />
            </Link>
          </div>
          {rulesLoading
            ? <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>{[...Array(3)].map((_, i) => <Skeleton key={i} height={48} />)}</div>
            : rules.slice(0, 4).map((r) => (
              <div key={r.id} style={{ display:'flex', alignItems:'center', gap:10, padding:'10px 0', borderBottom:'1px solid var(--border)' }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{r.name}</div>
                  <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{r.triggerType}</div>
                </div>
                <Badge variant={r.active ? 'green' : 'muted'}>{r.active ? 'Active' : 'Off'}</Badge>
              </div>
            ))
          }
          {!rulesLoading && rules.length === 0 && (
            <div style={{ textAlign: 'center', padding: '20px 0', color: 'var(--text-muted)', fontSize: 13 }}>
              No rules yet. <Link to="/rules" style={{ color: 'var(--accent-light)' }}>Create one →</Link>
            </div>
          )}
        </div>
      </div>
    </AppLayout>
  );
}

function timeOfDay() {
  const h = new Date().getHours();
  if (h < 12) return 'morning';
  if (h < 17) return 'afternoon';
  return 'evening';
}
