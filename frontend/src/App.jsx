import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AppProvider, useApp } from './context/AppContext';
import { ToastContainer } from './components/ui';
import LoginPage    from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import InboxPage    from './pages/InboxPage';
import RulesPage    from './pages/RulesPage';
import ConnectPage  from './pages/ConnectPage';
import OAuthCallbackPage from './pages/OAuthCallbackPage';

function ProtectedRoute({ children }) {
  const { user, bootstrapping } = useApp();
  if (bootstrapping) return null;
  return user ? children : <Navigate to="/login" replace />;
}

function PublicRoute({ children }) {
  const { user, bootstrapping } = useApp();
  if (bootstrapping) return null;
  return user ? <Navigate to="/" replace /> : children;
}

function AppRoutes() {
  return (
    <>
      <Routes>
        <Route path="/login" element={<PublicRoute><LoginPage /></PublicRoute>} />
        <Route path="/"       element={<ProtectedRoute><DashboardPage /></ProtectedRoute>} />
        <Route path="/inbox"  element={<ProtectedRoute><InboxPage /></ProtectedRoute>} />
        <Route path="/rules"  element={<ProtectedRoute><RulesPage /></ProtectedRoute>} />
        <Route path="/connect" element={<ProtectedRoute><ConnectPage /></ProtectedRoute>} />
        <Route path="/oauth/callback" element={<OAuthCallbackPage />} />
        <Route path="*"       element={<Navigate to="/" replace />} />
      </Routes>
      <ToastContainer />
    </>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AppProvider>
        <AppRoutes />
      </AppProvider>
    </BrowserRouter>
  );
}
