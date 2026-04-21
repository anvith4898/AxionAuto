import React from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { LayoutDashboard, MessageSquare, Zap, Link2, LogOut, Bot } from 'lucide-react';
import { useApp } from '../../context/AppContext';
import { useAuth } from '../../hooks/useAuth';

const navItems = [
  { to: '/',        icon: LayoutDashboard, label: 'Dashboard' },
  { to: '/inbox',   icon: MessageSquare,   label: 'Inbox'     },
  { to: '/rules',   icon: Zap,             label: 'Automation'},
  { to: '/connect', icon: Link2,           label: 'Connect'   },
];

export function Sidebar() {
  const { user } = useApp();
  const { logout } = useAuth();

  const initials = user?.name
    ? user.name.split(' ').map((w) => w[0]).join('').toUpperCase().slice(0, 2)
    : '?';

  return (
    <aside className="sidebar">
      {/* Logo */}
      <div className="sidebar-logo">
        <div className="sidebar-logo-icon">
          <Bot size={18} color="#fff" />
        </div>
        <span className="sidebar-logo-text">AxionAuto</span>
      </div>

      {/* Nav */}
      {navItems.map(({ to, icon: Icon, label }) => (
        <NavLink
          key={to}
          to={to}
          end={to === '/'}
          className={({ isActive }) =>
            `sidebar-nav-item ${isActive ? 'active' : ''}`
          }
        >
          <Icon size={16} />
          {label}
        </NavLink>
      ))}

      <div className="sidebar-spacer" />

      {/* User */}
      <div className="sidebar-user">
        <div className="sidebar-user-avatar">{initials}</div>
        <div className="sidebar-user-info">
          <div className="sidebar-user-name">{user?.name || user?.email || 'User'}</div>
          <div className="sidebar-user-role">{user?.role || 'Member'}</div>
        </div>
        <button
          className="btn btn-ghost btn-icon btn-sm"
          onClick={logout}
          title="Logout"
          style={{ padding: 6 }}
        >
          <LogOut size={15} />
        </button>
      </div>
    </aside>
  );
}

export function TopBar({ title, children }) {
  return (
    <div className="topbar">
      <span className="topbar-title">{title}</span>
      <div className="topbar-right">{children}</div>
    </div>
  );
}

export function AppLayout({ title, children, topBarRight }) {
  return (
    <div className="app-shell">
      <Sidebar />
      <div className="main-area">
        <TopBar title={title}>{topBarRight}</TopBar>
        <div className="page-content">{children}</div>
      </div>
    </div>
  );
}
