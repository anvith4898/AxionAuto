import React, { useState } from 'react';
import { Eye, EyeOff, Bot, ArrowRight } from 'lucide-react';
import { useAuth } from '../hooks/useAuth';
import { Spinner, ErrorBox } from '../components/ui';

export default function LoginPage() {
  const { login, loading, error } = useAuth();
  const [email,    setEmail]    = useState('demo@axion.io');
  const [password, setPassword] = useState('demo123');
  const [showPwd,  setShowPwd]  = useState(false);

  const handleSubmit = (e) => {
    e.preventDefault();
    login(email, password);
  };

  return (
    <div className="login-page">
      <div className="login-card">
        {/* Header */}
        <div className="login-header">
          <div className="login-logo">
            <Bot size={28} color="#fff" />
          </div>
          <h1 style={{ fontSize: 22, marginBottom: 4 }}>Welcome to AxionAuto</h1>
          <p style={{ fontSize: 13 }}>Instagram DM Automation Platform</p>
        </div>

        {/* Form */}
        <form className="login-form" onSubmit={handleSubmit}>
          {error && <ErrorBox message={error} />}

          <div className="input-group">
            <label className="input-label" htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              className="input"
              placeholder="you@company.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="email"
            />
          </div>

          <div className="input-group">
            <label className="input-label" htmlFor="password">Password</label>
            <div style={{ position: 'relative' }}>
              <input
                id="password"
                type={showPwd ? 'text' : 'password'}
                className="input"
                placeholder="••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                style={{ paddingRight: 44 }}
                autoComplete="current-password"
              />
              <button
                type="button"
                onClick={() => setShowPwd((v) => !v)}
                style={{
                  position: 'absolute', right: 12, top: '50%', transform: 'translateY(-50%)',
                  background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-muted)',
                  display: 'flex'
                }}
              >
                {showPwd ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
          </div>

          <button
            id="login-submit"
            type="submit"
            className="btn btn-primary btn-lg w-full"
            disabled={loading}
            style={{ marginTop: 4 }}
          >
            {loading ? <Spinner /> : <><span>Sign In</span><ArrowRight size={16} /></>}
          </button>
        </form>

        {/* Demo hint */}
        <div style={{ marginTop: 24 }}>
          <div className="login-divider">Demo credentials pre-filled</div>
          <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 6 }}>
            {[
              { label: 'Demo User', email: 'demo@axion.io', pwd: 'demo123' },
              { label: 'Admin',     email: 'admin@axion.io', pwd: 'admin123' },
            ].map((cred) => (
              <button
                key={cred.email}
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => { setEmail(cred.email); setPassword(cred.pwd); }}
              >
                Use {cred.label}: {cred.email}
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
