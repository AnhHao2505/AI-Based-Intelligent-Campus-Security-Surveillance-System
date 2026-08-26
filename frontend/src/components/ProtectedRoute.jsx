import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function ProtectedRoute({ allowedRoles, children }) {
  const { isAuthenticated, user, hasRole } = useAuth();

  if (!isAuthenticated) {
    // Nếu chưa đăng nhập, chuyển hướng về login
    return <Navigate to="/login" replace />;
  }

  if (allowedRoles && !hasRole(allowedRoles)) {
    // Nếu đã đăng nhập nhưng không đủ quyền, chuyển sang trang báo lỗi unauthorized
    return <Navigate to="/unauthorized" replace />;
  }

  // Nếu hợp lệ, render child component hoặc Outlet
  return children ? children : <Outlet />;
}
