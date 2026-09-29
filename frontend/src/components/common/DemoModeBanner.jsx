import { useAuth } from '../../context/AuthContext';
import { DEMO_LOGIN_ENABLED } from '../../config/demoConfig';

export default function DemoModeBanner() {
  const { token, isDemoMode } = useAuth();
  const showBanner =
    DEMO_LOGIN_ENABLED &&
    (isDemoMode || Boolean(token?.startsWith('frontend-demo-')));

  if (!showBanner) {
    return null;
  }

  return (
    <aside
      role="alert"
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        zIndex: 99999,
        backgroundColor: '#dc2626',
        color: '#ffffff',
        textAlign: 'center',
        padding: '6px 12px',
        fontSize: '13px',
        fontWeight: '700',
        letterSpacing: '0.02em',
        boxShadow: '0 2px 8px rgba(0, 0, 0, 0.25)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '8px',
      }}
    >
      <span>CHẾ ĐỘ DEMO – dữ liệu giả, thao tác không được lưu</span>
    </aside>
  );
}
