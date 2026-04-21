import React from 'react';
import { useApp } from '../../context/AppContext';
import { CheckCircle, AlertCircle, Info, X } from 'lucide-react';

export function ToastContainer() {
  const { toasts, dismissToast } = useApp();

  if (!toasts.length) return null;

  const icons = {
    success: <CheckCircle size={16} color="var(--green)" />,
    error:   <AlertCircle  size={16} color="var(--red)"  />,
    info:    <Info         size={16} color="var(--accent-light)" />,
  };

  return (
    <div className="toast-container">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast-${t.type}`}>
          {icons[t.type] || icons.info}
          <span style={{ flex: 1, fontSize: 13 }}>{t.message}</span>
          <button
            onClick={() => dismissToast(t.id)}
            style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-muted)', display: 'flex' }}
          >
            <X size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}

export function Spinner({ size = 'md' }) {
  return <div className={size === 'lg' ? 'spinner spinner-lg' : 'spinner'} />;
}

export function LoadingCenter({ label = 'Loading...' }) {
  return (
    <div className="loading-center">
      <Spinner size="lg" />
      <span>{label}</span>
    </div>
  );
}

export function ErrorBox({ message }) {
  return (
    <div className="error-box">
      <AlertCircle size={16} />
      {message}
    </div>
  );
}

export function Badge({ children, variant = 'muted' }) {
  return <span className={`badge badge-${variant}`}>{children}</span>;
}

export function Toggle({ on, onToggle, label }) {
  return (
    <label className="toggle-wrapper" onClick={onToggle} style={{ cursor: 'pointer' }}>
      <div className={`toggle ${on ? 'on' : ''}`}>
        <div className="toggle-thumb" />
      </div>
      {label && <span style={{ fontSize: 13, fontWeight: 500 }}>{label}</span>}
    </label>
  );
}

export function Skeleton({ width = '100%', height = 16, style }) {
  return <div className="skeleton" style={{ width, height, ...style }} />;
}
