import React, { createContext, useContext, useReducer, useEffect } from 'react';
import { getCurrentSession } from '../api/authApi';

// ── State shape ───────────────────────────────────────────────────────────
const initialState = {
  user: null,           // { userId, tenantId, name, role, email, token }
  igStatus: {           // Instagram connection
    connected: false,
    accountId: null,
    igUsername: null,
  },
  automationEnabled: true,
  toasts: [],
};

// ── Actions ───────────────────────────────────────────────────────────────
const ACTIONS = {
  SET_USER:           'SET_USER',
  CLEAR_USER:         'CLEAR_USER',
  SET_IG_STATUS:      'SET_IG_STATUS',
  TOGGLE_AUTOMATION:  'TOGGLE_AUTOMATION',
  ADD_TOAST:          'ADD_TOAST',
  DISMISS_TOAST:      'DISMISS_TOAST',
};

function reducer(state, action) {
  switch (action.type) {
    case ACTIONS.SET_USER:
      return { ...state, user: action.payload };
    case ACTIONS.CLEAR_USER:
      return { ...state, user: null, igStatus: initialState.igStatus };
    case ACTIONS.SET_IG_STATUS:
      return { ...state, igStatus: { ...state.igStatus, ...action.payload } };
    case ACTIONS.TOGGLE_AUTOMATION:
      return { ...state, automationEnabled: !state.automationEnabled };
    case ACTIONS.ADD_TOAST:
      return { ...state, toasts: [...state.toasts, { id: Date.now(), ...action.payload }] };
    case ACTIONS.DISMISS_TOAST:
      return { ...state, toasts: state.toasts.filter((t) => t.id !== action.payload) };
    default:
      return state;
  }
}

// ── Context ───────────────────────────────────────────────────────────────
const AppContext = createContext(null);

export function AppProvider({ children }) {
  const [state, dispatch] = useReducer(reducer, initialState, (init) => {
    // Hydrate user from localStorage on mount
    const session = localStorage.getItem('axion_session');
    const igStatus = localStorage.getItem('axion_ig_status');
    const autoEnabled = localStorage.getItem('axion_automation');
    return {
      ...init,
      user: session ? JSON.parse(session) : null,
      igStatus: igStatus ? JSON.parse(igStatus) : init.igStatus,
      automationEnabled: autoEnabled !== null ? JSON.parse(autoEnabled) : true,
    };
  });

  const [bootstrapping, setBootstrapping] = React.useState(true);

  // Persist to localStorage on change
  useEffect(() => {
    if (state.user) localStorage.setItem('axion_session', JSON.stringify(state.user));
    else localStorage.removeItem('axion_session');
  }, [state.user]);

  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      if (!state.user?.token) {
        if (!cancelled) setBootstrapping(false);
        return;
      }

      try {
        const session = await getCurrentSession();
        if (!cancelled) {
          dispatch({ type: ACTIONS.SET_USER, payload: session });
        }
      } catch {
        if (!cancelled) {
          dispatch({ type: ACTIONS.CLEAR_USER });
        }
      } finally {
        if (!cancelled) {
          setBootstrapping(false);
        }
      }
    }

    restoreSession();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    localStorage.setItem('axion_ig_status', JSON.stringify(state.igStatus));
  }, [state.igStatus]);

  useEffect(() => {
    localStorage.setItem('axion_automation', JSON.stringify(state.automationEnabled));
  }, [state.automationEnabled]);

  // Auto-dismiss toasts after 4 seconds
  useEffect(() => {
    if (state.toasts.length === 0) return;
    const latest = state.toasts[state.toasts.length - 1];
    const timer = setTimeout(() => dispatch({ type: ACTIONS.DISMISS_TOAST, payload: latest.id }), 4000);
    return () => clearTimeout(timer);
  }, [state.toasts]);

  // ── Helpers ───────────────────────────────────────────────────────────
  const setUser      = (user)   => dispatch({ type: ACTIONS.SET_USER,     payload: user });
  const clearUser    = ()       => dispatch({ type: ACTIONS.CLEAR_USER });
  const setIgStatus  = (s)      => dispatch({ type: ACTIONS.SET_IG_STATUS, payload: s });
  const toggleAuto   = ()       => dispatch({ type: ACTIONS.TOGGLE_AUTOMATION });
  const dismissToast = (id)     => dispatch({ type: ACTIONS.DISMISS_TOAST, payload: id });

  const toast = (message, type = 'info') =>
    dispatch({ type: ACTIONS.ADD_TOAST, payload: { message, type } });

  return (
    <AppContext.Provider
      value={{ ...state, bootstrapping, setUser, clearUser, setIgStatus, toggleAuto, toast, dismissToast }}
    >
      {children}
    </AppContext.Provider>
  );
}

export function useApp() {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useApp must be used within AppProvider');
  return ctx;
}
