# Frontend — React Dashboard

## Overview

AxionAuto's frontend is a React SPA (Single Page Application) built with Vite. It provides a dashboard for business operators to monitor automation metrics, manage DM conversations, create and edit automation rules, and connect their Instagram Business Account.

---

## Technology Stack

| Technology | Version | Purpose |
|---|---|---|
| React | 18+ | UI component framework |
| React Router DOM | 6+ | Client-side routing with `BrowserRouter` |
| Vite | 5+ | Dev server and production bundler |
| Vanilla CSS | — | Custom design system in `index.css` |
| Google Fonts (Inter) | — | Typography |

No CSS frameworks (Tailwind, Bootstrap) are used. All styling is custom-written in `index.css`.

---

## Directory Structure

```
frontend/src/
├── App.jsx          # Root app: BrowserRouter, route definitions, route guards
├── main.jsx         # React DOM root mount point (StrictMode)
├── main.ts          # (TypeScript entry for non-React code)
├── counter.ts       # (utility counter)
├── index.css        # Global design system (tokens, reset, components)
├── style.css        # Additional styles
├── api/             # API client functions (axios/fetch wrappers)
├── assets/          # Static assets (images, svgs)
├── components/
│   ├── layout/      # AppShell, Sidebar, TopBar
│   └── ui/          # Reusable: ToastContainer, Badge, Toggle, Spinner, etc.
├── context/
│   └── AppContext.jsx  # Global state: user, toast messages, automation status
├── hooks/           # Custom React hooks
└── pages/
    ├── DashboardPage.jsx   # Metrics overview
    ├── InboxPage.jsx       # DM conversation inbox
    ├── RulesPage.jsx       # Automation rule manager
    ├── ConnectPage.jsx     # Instagram account connection
    └── LoginPage.jsx       # Authentication
```

---

## Routing

Defined in `App.jsx`:

```jsx
<BrowserRouter>
  <AppProvider>
    <Routes>
      <Route path="/login"   element={<PublicRoute><LoginPage /></PublicRoute>} />
      <Route path="/"        element={<ProtectedRoute><DashboardPage /></ProtectedRoute>} />
      <Route path="/inbox"   element={<ProtectedRoute><InboxPage /></ProtectedRoute>} />
      <Route path="/rules"   element={<ProtectedRoute><RulesPage /></ProtectedRoute>} />
      <Route path="/connect" element={<ProtectedRoute><ConnectPage /></ProtectedRoute>} />
      <Route path="*"        element={<Navigate to="/" replace />} />
    </Routes>
    <ToastContainer />
  </AppProvider>
</BrowserRouter>
```

**Route guards:**
- `ProtectedRoute`: redirects to `/login` if `user` is not in context
- `PublicRoute`: redirects to `/` if `user` is already authenticated

---

## Pages

### DashboardPage (`/`)

Displays high-level automation metrics:
- Stat cards: messages processed, rules fired, active accounts, DMs sent
- Activity charts / recent execution log (connected to the backend `/stats` endpoint)
- Automation enable/disable banner toggle

### InboxPage (`/inbox`)

Two-panel conversation inbox:
- **Left panel** (`thread-list`): List of conversation threads sorted by recency. Each item shows avatar, name, last message preview, timestamp, and unread indicator dot.
- **Right panel** (`message-area`): Full conversation history rendered as chat bubbles (inbound dark, outbound purple gradient). Message composer at the bottom.

### RulesPage (`/rules`)

Split-panel rule management:
- **Left panel** (`rules-list`): Cards for each automation rule showing name, trigger type badge, keyword tags, reply text preview, and active toggle.
- **Right panel** (`rule-form-panel`): Inline editor for creating or editing a rule. Fields: name, trigger type, execution mode, reply text, keywords (tag-based input), cooldown, priority, active toggle.
  - Slides in from the right with a CSS keyframe animation (`slide-from-right`).

### ConnectPage (`/connect`)

Instagram account connection flow:
- Card showing current connection status (connected/disconnected)
- "Connect with Instagram" button — redirects to `/api/v1/oauth/instagram/authorize`
- Post-connection: displays connected account avatar, username, expiry, and a "Disconnect" button

### LoginPage (`/login`)

Minimal login form:
- Email + password fields
- Logo, gradient background
- Submits credentials to backend auth
- On success → sets `user` in `AppContext` → `ProtectedRoute` grants access

---

## Design System (`index.css`)

### CSS Custom Properties (Design Tokens)

```css
:root {
  /* Backgrounds */
  --bg-base:         #070a12;   /* page background (near-black) */
  --bg-surface:      #0d1117;   /* card surfaces */
  --bg-elevated:     #161b26;   /* inputs, elevated cards */
  --bg-glass:        rgba(22, 27, 38, 0.7);   /* glassmorphism */
  --bg-glass-hover:  rgba(30, 38, 55, 0.85);

  /* Brand colors */
  --accent:          #7c3aed;   /* primary purple */
  --accent-light:    #9f67ff;
  --accent-dim:      rgba(124, 58, 237, 0.15);
  --accent-border:   rgba(124, 58, 237, 0.4);
  --cyan:            #06b6d4;
  --cyan-dim:        rgba(6, 182, 212, 0.12);

  /* Semantic */
  --green:           #10b981;
  --red:             #ef4444;
  --yellow:          #f59e0b;

  /* Typography */
  --text-primary:    #f0f4ff;
  --text-secondary:  #8b9ab5;
  --text-muted:      #4b5a72;

  /* Borders */
  --border:          rgba(255, 255, 255, 0.06);
  --border-strong:   rgba(255, 255, 255, 0.12);

  /* Spacing */
  --radius-sm: 6px;   --radius-md: 10px;
  --radius-lg: 16px;  --radius-xl: 22px;

  /* Shadows */
  --shadow-glow: 0 0 30px rgba(124, 58, 237, 0.2);

  /* Layout */
  --sidebar-w: 240px;
  --topbar-h:  60px;
}
```

### Layout Classes

| Class | Purpose |
|---|---|
| `.app-shell` | `display: flex` root container |
| `.sidebar` | Fixed-width `240px` glassmorphic left sidebar |
| `.main-area` | Flex column: topbar + page content |
| `.page-content` | `overflow-y: auto` scrollable main area |

### Component Classes

| Category | Classes |
|---|---|
| Cards | `.card`, `.stat-card`, `.rule-card`, `.connect-card` |
| Buttons | `.btn`, `.btn-primary`, `.btn-secondary`, `.btn-ghost`, `.btn-danger`, `.btn-sm`, `.btn-lg`, `.btn-icon` |
| Badges | `.badge`, `.badge-green`, `.badge-red`, `.badge-accent`, `.badge-cyan`, `.badge-muted` |
| Inputs | `.input`, `.input-group`, `.input-label` |
| Feedback | `.spinner`, `.spinner-lg`, `.toast`, `.toast-success`, `.toast-error`, `.toast-info` |
| Toggle | `.toggle`, `.toggle.on`, `.toggle-thumb` |
| Skeleton | `.skeleton` (shimmer animation) |
| Empty state | `.empty-state`, `.empty-state-icon` |

### Animations

- `@keyframes spin` — used by `.spinner` for loading indicators
- `@keyframes shimmer` — used by `.skeleton` for loading placeholders
- `@keyframes slide-in` — used by `.toast` for slide-in from right
- `@keyframes slide-from-right` — used by `.rule-form-panel` slide-in

---

## AppContext

Global React context providing:

```js
{
  user: null | { tenantId, userId, displayName, ... },
  setUser: fn,
  toasts: [],
  addToast: fn(message, type),   // type: 'success' | 'error' | 'info'
  removeToast: fn(id),
  automationEnabled: bool,
  setAutomationEnabled: fn
}
```

Used by all pages to access auth state and trigger UI notifications.

---

## Responsive Breakpoints

```css
@media (max-width: 900px) {
  .dashboard-grid { grid-template-columns: 1fr; }
  .inbox-layout   { flex-direction: column; }
  .thread-list    { width: 100%; height: 220px; border-right: none; }
  .connect-grid   { grid-template-columns: 1fr; }
}
```

On small screens: thread list stacks above the message area; dashboard grid becomes single column; connect grid becomes single column.
