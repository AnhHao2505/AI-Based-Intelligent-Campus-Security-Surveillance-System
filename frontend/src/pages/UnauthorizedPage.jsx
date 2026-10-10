import { useNavigate } from 'react-router-dom';
import { ShieldOff } from 'lucide-react';
import { EmptyState } from '../components/ui';

export default function UnauthorizedPage() {
  const navigate = useNavigate();
  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '16px',
        background: 'var(--theme-bg-page)',
      }}
    >
      <div
        style={{
          maxWidth: '480px',
          width: '100%',
          background: 'var(--theme-bg-surface)',
          border: '1px solid var(--theme-border)',
          borderRadius: '12px',
        }}
      >
        <EmptyState
          icon={ShieldOff}
          title="Không có quyền truy cập"
          description="Tài khoản của bạn không được phân quyền để truy cập trang này. Vui lòng liên hệ quản trị viên nếu bạn cho rằng đây là nhầm lẫn."
          actionLabel="Quay lại trang chủ"
          onAction={() => navigate('/')}
        />
      </div>
    </div>
  );
}
