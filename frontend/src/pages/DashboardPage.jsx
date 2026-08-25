import './DashboardPage.css';

const ROLE_LABELS = {
  ADMIN: 'Quản trị viên',
  FACILITY_MANAGER: 'Quản lý cơ sở',
  INTERNAL_GUARD: 'Bảo vệ nội bộ',
  OUTSOURCED_GUARD: 'Bảo vệ thuê ngoài',
};

export default function DashboardPage({ user, onLogout }) {
  const handleLogout = () => {
    if (onLogout) {
      onLogout();
    }
  };

  const fullName = user?.fullName || user?.full_name || 'User';
  const userCode = user?.userCode || user?.user_code || user?.staffCode || '';
  const roleType = user?.roleType || user?.role_type || '';

  return (
    <div className="dashboard">
      <div className="dashboard-bg">
        <div className="dashboard-bg-orb dashboard-bg-orb--1" />
        <div className="dashboard-bg-orb dashboard-bg-orb--2" />
      </div>

      <nav className="dashboard-nav">
        <div className="dashboard-nav__brand">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            <path d="M2 17L12 22L22 17" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            <path d="M2 12L12 17L22 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
          <span>Campus Security</span>
        </div>
        <button className="dashboard-nav__logout" onClick={handleLogout}>
          Đăng xuất
        </button>
      </nav>

      <main className="dashboard-main">
        <div className="dashboard-welcome">
          <h1>Xin chào, {fullName} 👋</h1>
          <p>Chào mừng bạn đến với Hệ thống Giám sát An ninh Thông minh</p>
        </div>

        <div className="dashboard-grid">
          <div className="dashboard-card dashboard-card--profile">
            <h3>Thông tin cá nhân</h3>
            <div className="dashboard-card__rows">
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Họ tên</span>
                <span className="dashboard-card__value">{fullName}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Email</span>
                <span className="dashboard-card__value">{user?.email}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Mã người dùng</span>
                <span className="dashboard-card__value">{userCode}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Vai trò</span>
                <span className="dashboard-card__value dashboard-card__role-badge">
                  {ROLE_LABELS[roleType] || roleType}
                </span>
              </div>
            </div>
          </div>

          <div className="dashboard-card dashboard-card--status">
            <h3>Trạng thái hệ thống</h3>
            <div className="dashboard-card__rows">
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Xác thực</span>
                <span className="dashboard-card__status dashboard-card__status--ok">
                  ● Đã đăng nhập
                </span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Token</span>
                <span className="dashboard-card__status dashboard-card__status--ok">
                  ● Hợp lệ (24h)
                </span>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
