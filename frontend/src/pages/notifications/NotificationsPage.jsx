import { useState, useEffect } from 'react';
import {
  Bell,
  BellOff,
  Clock,
  ShieldX,
  CircleCheck,
  CircleX,
  RefreshCw
} from 'lucide-react';
import '../../styles/NotificationsPage.css';

export default function NotificationsPage() {
  // 3e. Cấu trúc chờ nối API
  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(false);

  // TODO: nối API khi backend có bảng notifications
  // Dự kiến: GET  /api/notifications/my?page=&size=
  //          PATCH /api/notifications/{id}/read
  //          PATCH /api/notifications/read-all
  // Trả về: { content: [{ id, type, title, message, createdAt, isRead }], ... }
  const fetchNotifications = async () => {
    // chưa hiện thực
  };

  const handleMarkAllAsRead = async () => {
    // chưa hiện thực: gọi PATCH /api/notifications/read-all
  };

  const handleItemClick = async (notif) => {
    // chưa hiện thực: nếu !notif.isRead -> gọi PATCH /api/notifications/{id}/read
  };

  useEffect(() => {
    fetchNotifications();
  }, []);

  const formatTime = (ts) => {
    if (!ts) return '';
    try {
      const d = new Date(ts);
      if (isNaN(d.getTime())) return ts;
      return d.toLocaleString('vi-VN', {
        hour: '2-digit',
        minute: '2-digit',
        day: '2-digit',
        month: '2-digit',
        year: 'numeric'
      });
    } catch {
      return ts;
    }
  };

  const renderIcon = (type) => {
    switch (type) {
      case 'EXPIRING_SOON':
      case 'EXPIRING':
      case 'Sắp hết hạn':
        return (
          <div className="notif-icon-box notif-icon-box--expiring">
            <Clock size={16} />
          </div>
        );
      case 'ACCESS_DENIED':
      case 'Bị từ chối truy cập':
        return (
          <div className="notif-icon-box notif-icon-box--denied">
            <ShieldX size={16} />
          </div>
        );
      case 'REQUEST_APPROVED':
      case 'Yêu cầu được duyệt':
        return (
          <div className="notif-icon-box notif-icon-box--approved">
            <CircleCheck size={16} />
          </div>
        );
      case 'REQUEST_REJECTED':
      case 'Yêu cầu bị từ chối':
        return (
          <div className="notif-icon-box notif-icon-box--rejected">
            <CircleX size={16} />
          </div>
        );
      default:
        return (
          <div className="notif-icon-box notif-icon-box--default">
            <Bell size={16} />
          </div>
        );
    }
  };

  return (
    <div className="notif-container">
      {/* 3b. Một thẻ duy nhất: Thông báo */}
      <div className="notif-card">
        {/* Đầu thẻ */}
        <div className="notif-card__header">
          <div className="notif-card__header-left">
            <div className="notif-card__icon-box">
              <Bell size={16} />
            </div>
            <div>
              <h2 className="notif-card__title">Thông báo</h2>
              <p className="notif-card__subtitle">
                Cập nhật về yêu cầu truy cập và quyền của bạn
              </p>
            </div>
          </div>

          {/* Bên phải: Liên kết "Đánh dấu đã đọc tất cả" */}
          <button
            type="button"
            className="notif-mark-all-btn"
            disabled={notifications.length === 0 || loading}
            onClick={handleMarkAllAsRead}
          >
            Đánh dấu đã đọc tất cả
          </button>
        </div>

        {/* 3c. Danh sách thông báo / 3d. Trạng thái rỗng */}
        {loading ? (
          <div className="notif-empty">
            <RefreshCw size={24} className="notif-spin notif-empty__icon" />
            <div className="notif-empty__title">Đang tải thông báo...</div>
          </div>
        ) : notifications.length === 0 ? (
          /* 3d. TRẠNG THÁI RỖNG */
          <div className="notif-empty">
            <BellOff size={32} strokeWidth={1.5} className="notif-empty__icon" />
            <div className="notif-empty__title">Chưa có thông báo nào</div>
            <div className="notif-empty__subtitle">
              Bạn sẽ nhận được thông báo khi yêu cầu truy cập được xử lý
            </div>
          </div>
        ) : (
          /* 3c. Danh sách thông báo (dạng danh sách dòng, không dùng bảng) */
          <div className="notif-list">
            {notifications.map((notif) => (
              <div
                key={notif.id}
                className={`notif-item ${
                  notif.isRead ? 'notif-item--read' : 'notif-item--unread'
                }`}
                onClick={() => handleItemClick(notif)}
              >
                {/* Cột 1 — icon tròn 32px */}
                {renderIcon(notif.type)}

                {/* Cột 2 — tiêu đề & nội dung */}
                <div className="notif-item__content">
                  <div className="notif-item__title-row">
                    <span className="notif-item__title">{notif.title}</span>
                    {!notif.isRead && <span className="notif-item__unread-dot" />}
                  </div>
                  <div className="notif-item__message">{notif.message}</div>
                </div>

                {/* Cột 3 — thời gian */}
                <div className="notif-item__time">
                  {formatTime(notif.createdAt)}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
