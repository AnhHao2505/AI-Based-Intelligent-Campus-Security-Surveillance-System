import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Bell,
  BellOff,
  Clock,
  ShieldX,
  CircleCheck,
  CircleX,
  RefreshCw,
  Users,
  Inbox,
  XCircle,
  AlarmClock
} from 'lucide-react';
import { notificationService } from '../../services/notificationService';
import { useAuth } from '../../context/AuthContext';
import Pagination from '../../components/ui/Pagination';
import '../../styles/NotificationsPage.css';

export default function NotificationsPage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const pageSize = 20;

  const fetchNotifications = async (pageIndex = page) => {
    try {
      setLoading(true);
      const res = await notificationService.getMyNotifications({ page: pageIndex, size: pageSize });
      setNotifications(res.content || []);
      setPage(res.number ?? pageIndex);
      setTotalPages(res.totalPages ?? 1);
      setTotalElements(res.totalElements ?? (res.content?.length || 0));
    } catch (err) {
      console.error('Lỗi khi tải thông báo:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleMarkAllAsRead = async () => {
    try {
      await notificationService.markAllAsRead();
      setNotifications((prev) => prev.map((notif) => ({ ...notif, isRead: true })));
      window.dispatchEvent(new CustomEvent('notification-updated'));
    } catch (err) {
      console.error('Lỗi khi đánh dấu đã đọc tất cả:', err);
    }
  };

  const handleItemClick = async (notif) => {
    if (!notif.isRead) {
      // Optimistic update
      setNotifications((prev) =>
        prev.map((item) => (item.id === notif.id ? { ...item, isRead: true } : item))
      );
      try {
        await notificationService.markAsRead(notif.id);
        window.dispatchEvent(new CustomEvent('notification-updated'));
      } catch (err) {
        console.error('Lỗi khi đánh dấu đã đọc thông báo:', err);
      }
    }

    if (notif.referenceType === 'ACCESS_REQUEST' || notif.reference_type === 'ACCESS_REQUEST') {
      const role = user?.role || user?.role_type || '';
      if (role === 'FACILITY_MANAGER' || role === 'ADMIN') {
        navigate('/admin/access-requests');
      } else {
        navigate('/access-requests');
      }
    }
  };

  useEffect(() => {
    fetchNotifications(0);
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
          <div className="notif-icon-box notif-icon-box--expiring" title="Sắp tới giờ">
            <Clock size={16} />
          </div>
        );
      case 'ACCESS_DENIED':
      case 'Bị từ chối truy cập':
        return (
          <div className="notif-icon-box notif-icon-box--denied" title="Hết hạn / Từ chối">
            <ShieldX size={16} />
          </div>
        );
      case 'REQUEST_APPROVED':
      case 'Yêu cầu được duyệt':
        return (
          <div className="notif-icon-box notif-icon-box--approved" title="Đã phê duyệt">
            <CircleCheck size={16} />
          </div>
        );
      case 'REQUEST_REJECTED':
      case 'Yêu cầu bị từ chối':
        return (
          <div className="notif-icon-box notif-icon-box--rejected" title="Bị từ chối">
            <CircleX size={16} />
          </div>
        );
      case 'ADDED_TO_GROUP':
        return (
          <div className="notif-icon-box notif-icon-box--group" title="Thêm vào nhóm">
            <Users size={16} />
          </div>
        );
      case 'NEW_REQUEST_PENDING':
        return (
          <div className="notif-icon-box notif-icon-box--pending" title="Yêu cầu mới chờ duyệt">
            <Inbox size={16} />
          </div>
        );
      case 'REQUEST_CANCELLED':
        return (
          <div className="notif-icon-box notif-icon-box--cancelled" title="Yêu cầu đã huỷ">
            <XCircle size={16} />
          </div>
        );
      case 'PENDING_OVERDUE':
        return (
          <div className="notif-icon-box notif-icon-box--overdue" title="Tồn đọng quá 24 giờ">
            <AlarmClock size={16} />
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

  const hasUnread = notifications.some((n) => !n.isRead);

  return (
    <div className="notif-container">
      <div className="notif-card">
        {/* Header */}
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

          <button
            type="button"
            className="notif-mark-all-btn"
            disabled={!hasUnread || loading}
            onClick={handleMarkAllAsRead}
          >
            Đánh dấu đã đọc tất cả
          </button>
        </div>

        {/* List / Empty State */}
        {loading && notifications.length === 0 ? (
          <div className="notif-empty">
            <RefreshCw size={24} className="notif-spin notif-empty__icon" />
            <div className="notif-empty__title">Đang tải thông báo...</div>
          </div>
        ) : notifications.length === 0 ? (
          <div className="notif-empty">
            <BellOff size={32} strokeWidth={1.5} className="notif-empty__icon" />
            <div className="notif-empty__title">Chưa có thông báo nào</div>
            <div className="notif-empty__subtitle">
              Bạn sẽ nhận được thông báo khi yêu cầu truy cập được xử lý
            </div>
          </div>
        ) : (
          <>
            <div className="notif-list">
              {notifications.map((notif) => (
                <div
                  key={notif.id}
                  className={`notif-item ${
                    notif.isRead ? 'notif-item--read' : 'notif-item--unread'
                  }`}
                  onClick={() => handleItemClick(notif)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      handleItemClick(notif);
                    }
                  }}
                >
                  {/* Cột 1 — Icon */}
                  {renderIcon(notif.type)}

                  {/* Cột 2 — Tiêu đề & Nội dung */}
                  <div className="notif-item__content">
                    <div className="notif-item__title-row">
                      <span className="notif-item__title">{notif.title}</span>
                      {!notif.isRead && <span className="notif-item__unread-dot" />}
                    </div>
                    <div className="notif-item__message">{notif.message}</div>
                  </div>

                  {/* Cột 3 — Thời gian */}
                  <div className="notif-item__time">
                    {formatTime(notif.createdAt)}
                  </div>
                </div>
              ))}
            </div>

            {totalPages > 1 && (
              <div className="notif-pagination-wrapper">
                <Pagination
                  currentPage={page}
                  totalPages={totalPages}
                  totalElements={totalElements}
                  pageSize={pageSize}
                  onPageChange={(newPage) => {
                    setPage(newPage);
                    fetchNotifications(newPage);
                  }}
                  itemLabel="thông báo"
                />
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
