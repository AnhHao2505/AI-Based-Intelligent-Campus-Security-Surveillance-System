import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function ProtectedRoute({ children, allowedRoles }) {
  const { user, isAuthenticated } = useAuth();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (allowedRoles && !allowedRoles.includes(user.role_type)) {
    return (
      <div style={{ padding: '40px', textAlign: 'center' }}>
        <h2>403 - Access Denied</h2>
        <p>Bạn không có quyền truy cập vào trang này với vai trò [{user.role_type}].</p>
      </div>
    );
  }

  return children;
}
