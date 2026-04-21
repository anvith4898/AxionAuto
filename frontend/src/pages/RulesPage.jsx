import React, { useState } from 'react';
import { Plus, Zap, Trash2, Edit2, X, AlertCircle } from 'lucide-react';
import { AppLayout } from '../components/layout';
import { LoadingCenter, ErrorBox, Badge, Toggle, Spinner } from '../components/ui';
import { useRules } from '../hooks/useRules';
import { useApp } from '../context/AppContext';

const TRIGGER_LABELS = {
  KEYWORD:  { label: 'Keyword',  variant: 'accent' },
  WELCOME:  { label: 'Welcome',  variant: 'cyan'   },
  FALLBACK: { label: 'Fallback', variant: 'muted'  },
};

// ── Rule Form ─────────────────────────────────────────────────────────────
function RuleForm({ initial, onSave, onClose, saving }) {
  const [name,        setName]        = useState(initial?.name        || '');
  const [triggerType, setTriggerType] = useState(initial?.triggerType || 'KEYWORD');
  const [keywords,    setKeywords]    = useState(initial?.keywords    || []);
  const [keyInput,    setKeyInput]    = useState('');
  const [replyText,   setReplyText]   = useState(initial?.replyText   || '');
  const [priority,    setPriority]    = useState(initial?.priority    ?? 100);
  const [cooldown,    setCooldown]    = useState(initial?.cooldownSeconds ?? 3600);
  const [formError,   setFormError]   = useState('');

  const addKeyword = () => {
    const kw = keyInput.trim().toLowerCase();
    if (kw && !keywords.includes(kw)) {
      setKeywords((prev) => [...prev, kw]);
      setKeyInput('');
    }
  };

  const removeKeyword = (kw) => setKeywords((prev) => prev.filter((k) => k !== kw));

  const handleSubmit = (e) => {
    e.preventDefault();
    setFormError('');
    if (!name.trim())       { setFormError('Rule name is required.'); return; }
    if (!replyText.trim())  { setFormError('Reply text is required.'); return; }
    if (triggerType === 'KEYWORD' && keywords.length === 0) {
      setFormError('Add at least one keyword.'); return;
    }
    onSave({ name: name.trim(), triggerType, keywords, replyText: replyText.trim(), priority: +priority, cooldownSeconds: +cooldown });
  };

  return (
    <div className="rule-form-panel">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <h3>{initial ? 'Edit Rule' : 'New Rule'}</h3>
        <button className="btn btn-ghost btn-icon btn-sm" onClick={onClose}><X size={16} /></button>
      </div>

      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {formError && <ErrorBox message={formError} />}

        <div className="input-group">
          <label className="input-label">Rule Name</label>
          <input id="rule-name" className="input" placeholder="e.g. Pricing Inquiry" value={name} onChange={(e) => setName(e.target.value)} />
        </div>

        <div className="input-group">
          <label className="input-label">Trigger Type</label>
          <select id="rule-trigger" className="select input" value={triggerType} onChange={(e) => setTriggerType(e.target.value)}>
            <option value="KEYWORD">Keyword Match</option>
            <option value="WELCOME">Welcome (first message)</option>
            <option value="FALLBACK">Fallback (catch-all)</option>
          </select>
        </div>

        {triggerType === 'KEYWORD' && (
          <div className="input-group">
            <label className="input-label">Keywords</label>
            <div className="keyword-input-row">
              <input
                id="keyword-input"
                className="input"
                placeholder="Type a keyword and press Enter"
                value={keyInput}
                onChange={(e) => setKeyInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addKeyword(); } }}
              />
              <button type="button" className="btn btn-secondary btn-sm" onClick={addKeyword}>Add</button>
            </div>
            {keywords.length > 0 && (
              <div className="keyword-tags">
                {keywords.map((kw) => (
                  <span key={kw} className="keyword-tag">
                    {kw}
                    <button type="button" onClick={() => removeKeyword(kw)}><X size={10} /></button>
                  </span>
                ))}
              </div>
            )}
          </div>
        )}

        <div className="input-group">
          <label className="input-label">Auto-Reply Text</label>
          <textarea
            id="rule-reply"
            className="input"
            placeholder="Message to send when rule triggers..."
            value={replyText}
            onChange={(e) => setReplyText(e.target.value)}
            rows={4}
          />
          <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
            Tip: Use <code style={{ color: 'var(--accent-light)' }}>{'{sender_id}'}</code> for personalization.
          </span>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div className="input-group">
            <label className="input-label">Priority</label>
            <input id="rule-priority" type="number" className="input" value={priority} min={1} max={999} onChange={(e) => setPriority(e.target.value)} />
          </div>
          <div className="input-group">
            <label className="input-label">Cooldown (s)</label>
            <input id="rule-cooldown" type="number" className="input" value={cooldown} min={0} onChange={(e) => setCooldown(e.target.value)} />
          </div>
        </div>

        <div style={{ display: 'flex', gap: 10, marginTop: 4 }}>
          <button type="button" className="btn btn-secondary" style={{ flex: 1 }} onClick={onClose}>Cancel</button>
          <button id="save-rule-btn" type="submit" className="btn btn-primary" style={{ flex: 1 }} disabled={saving}>
            {saving ? <Spinner /> : (initial ? 'Update Rule' : 'Create Rule')}
          </button>
        </div>
      </form>
    </div>
  );
}

// ── Rules Page ─────────────────────────────────────────────────────────────
export default function RulesPage() {
  const { automationEnabled, toggleAuto } = useApp();
  const { rules, loading, saving, error, addRule, editRule, toggle, remove } = useRules();
  const [showForm,    setShowForm]    = useState(false);
  const [editingRule, setEditingRule] = useState(null);

  const handleSave = async (data) => {
    if (editingRule) {
      await editRule(editingRule.id, data);
    } else {
      await addRule(data);
    }
    setShowForm(false);
    setEditingRule(null);
  };

  const handleEdit = (rule) => { setEditingRule(rule); setShowForm(true); };
  const handleNewRule = () => { setEditingRule(null); setShowForm(true); };
  const handleClose = () => { setShowForm(false); setEditingRule(null); };

  return (
    <AppLayout title="Automation Rules">
      <div className="rules-layout" style={{ margin: '-28px', height: 'calc(100vh - var(--topbar-h))' }}>
        {/* Left: list */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          {/* Toolbar */}
          <div className="rules-toolbar">
            <div>
              <h2 style={{ fontSize: 16 }}>Automation Rules</h2>
              <p style={{ fontSize: 12, margin: 0 }}>{rules.length} rules · {rules.filter(r => r.active).length} active</p>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>Automation</span>
                <Toggle on={automationEnabled} onToggle={toggleAuto} />
              </div>
              <button id="new-rule-btn" className="btn btn-primary btn-sm" onClick={handleNewRule}>
                <Plus size={14} /> New Rule
              </button>
            </div>
          </div>

          {/* Content */}
          <div className="rules-list">
            {error && <ErrorBox message={error} />}

            {loading
              ? <LoadingCenter label="Loading rules..." />
              : rules.length === 0
                ? (
                  <div className="no-rules">
                    <Zap size={40} strokeWidth={1} />
                    <h3 style={{ color: 'var(--text-secondary)' }}>No rules yet</h3>
                    <p style={{ fontSize: 13 }}>Create your first automation rule to start auto-replying.</p>
                    <button className="btn btn-primary" onClick={handleNewRule}><Plus size={14} /> Create Rule</button>
                  </div>
                )
                : rules.map((rule) => {
                    const trigger = TRIGGER_LABELS[rule.triggerType] || { label: rule.triggerType, variant: 'muted' };
                    return (
                      <div key={rule.id} className={`rule-card ${rule.active ? '' : 'inactive'}`}>
                        <div className="rule-card-icon">
                          <Zap size={18} />
                        </div>
                        <div className="rule-card-body">
                          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <span className="rule-card-name">{rule.name}</span>
                            <Badge variant={trigger.variant}>{trigger.label}</Badge>
                            <Badge variant={rule.active ? 'green' : 'muted'}>{rule.active ? 'Active' : 'Off'}</Badge>
                          </div>
                          {rule.keywords?.length > 0 && (
                            <div className="rule-card-keywords">
                              {rule.keywords.slice(0, 5).map((kw) => (
                                <span key={kw} className="keyword-tag" style={{ fontSize: 11 }}>{kw}</span>
                              ))}
                              {rule.keywords.length > 5 && (
                                <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>+{rule.keywords.length - 5}</span>
                              )}
                            </div>
                          )}
                          <div className="rule-card-reply">↩ {rule.replyText}</div>
                          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 6 }}>
                            Priority {rule.priority} · {rule.cooldownSeconds ? `${rule.cooldownSeconds}s cooldown` : 'No cooldown'}
                          </div>
                        </div>
                        <div className="rule-card-actions">
                          <Toggle on={rule.active} onToggle={() => toggle(rule.id, rule.active)} />
                          <button
                            id={`edit-rule-${rule.id}`}
                            className="btn btn-ghost btn-icon btn-sm"
                            onClick={() => handleEdit(rule)}
                            title="Edit"
                          >
                            <Edit2 size={14} />
                          </button>
                          <button
                            id={`delete-rule-${rule.id}`}
                            className="btn btn-danger btn-icon btn-sm"
                            onClick={() => remove(rule.id)}
                            title="Delete"
                          >
                            <Trash2 size={14} />
                          </button>
                        </div>
                      </div>
                    );
                  })
            }
          </div>
        </div>

        {/* Right: form panel */}
        {showForm && (
          <RuleForm
            initial={editingRule}
            onSave={handleSave}
            onClose={handleClose}
            saving={saving}
          />
        )}
      </div>
    </AppLayout>
  );
}
