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
  AlarmClock,
  CalendarCheck,
  CalendarClock,
  CalendarX,
  Hourglass,
  SlidersHorizontal,
  ArrowLeftRight,
  Camera,
  UserCheck,
  UserMinus,
  CalendarOff,
  CheckCheck,
  ShieldCheck
} from 'lucide-react';
import { notificationService } from '../../services/notificationService';
import { useAuth } from '../../context/AuthContext';
import Pagination from '../../components/ui/Pagination';
import '../../styles/NotificationsPage.css';
import PageHeader from '../../components/ui/PageHeader';
import '../../components/ui/Button.css';
import { formatDateTime } from '../../utils/formatDateTime';
import { toast } from 'sonner';

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

    // BR-NT-26: thông báo đơn truy cập / lượt khách cũ đã lỡ gửi cho ADMIN (ADMIN không duyệt đơn, không duyệt / làm host khách)
    // -> chỉ đánh dấu đã đọc, không chuyển trang. GUEST_PHOTO_REQUIRED vẫn dẫn tới trang Ảnh khách.
    const notifRefType = notif.referenceType || notif.reference_type;
    if ((user?.role || user?.role_type) === 'ADMIN' && notif.type !== 'GUEST_PHOTO_REQUIRED'
      && (notifRefType === 'ACCESS_REQUEST' || notifRefType === 'GUEST_VISIT' || notif.type?.startsWith('GUEST_'))) {
      toast.info('Thông báo này dành cho Quản lý cơ sở vật chất');
      return;
    }

    if (notif.referenceType === 'ACCESS_REQUEST' || notif.reference_type === 'ACCESS_REQUEST') {
      const role = user?.role || user?.role_type || '';
      // Mở đúng đơn: trang đích đọc ?requestId (tô sáng dòng hoặc mở popup chi tiết)
      const refId = notif.referenceId || notif.reference_id;
      const query = refId ? `?requestId=${encodeURIComponent(refId)}` : '';
      if (role === 'FACILITY_MANAGER' || role === 'ADMIN') {
        navigate(`/admin/access-requests${query}`);
      } else {
        navigate(`/access-requests${query}`);
      }
    }

    // A-05: thông báo khu vực / chế độ sự kiện (BE gắn referenceType = "AREA") -> màn Quản lý khu vực.
    // GUARD (nhận EVENT_MODE_CHANGED) không có màn này nên chỉ đánh dấu đã đọc.
    if (notif.referenceType === 'AREA' || notif.reference_type === 'AREA') {
      const role = user?.role || user?.role_type || '';
      if (role === 'FACILITY_MANAGER' || role === 'ADMIN') {
        navigate('/admin/areas');
      }
      return;
    }

    // Step 6 (H1): quyền chỉ định ra vào bị thu hồi khi khu vực bị vô hiệu hoá
    if (notif.type === 'ACCESS_PERMISSION_REVOKED') {
      const role = user?.role || user?.role_type || '';
      if (role === 'FACILITY_MANAGER' || role === 'ADMIN') {
        navigate('/admin/areas');
      } else if (role === 'NORMAL_USER') {
        navigate('/access-requests');
      }
      return;
    }

    if (notif.referenceType === 'GUEST_VISIT' || notif.reference_type === 'GUEST_VISIT' || notif.type?.startsWith('GUEST_')) {
      // Mở đúng lượt khách: trang đích đọc ?visitId (tô sáng dòng hoặc mở popup chi tiết)
      const visitRef = notif.referenceId || notif.reference_id;
      const visitQuery = visitRef ? `?visitId=${encodeURIComponent(visitRef)}` : '';
      if (notif.type === 'GUEST_VISIT_PENDING') {
        navigate(`/admin/guest-visits${visitQuery}`);
      } else if (notif.type === 'GUEST_PHOTO_REQUIRED') {
        navigate('/admin/guest-photos');
      } else if ((user?.role || user?.role_type) === 'ADMIN') {
        // B-04: ADMIN không có trang "Khách của tôi"
        navigate(`/admin/guest-visits${visitQuery}`);
      } else {
        navigate(`/guest-visits${visitQuery}`);
      }
    }
  };

  useEffect(() => {
    fetchNotifications(0);
  }, []);


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
          <div className="notif-icon-box notif-icon-box--cancelled" title="Yêu cầu đã hủy">
            <XCircle size={16} />
          </div>
        );
      case 'PENDING_OVERDUE':
        return (
          <div className="notif-icon-box notif-icon-box--overdue" title="Tồn đọng quá 24 giờ">
            <AlarmClock size={16} />
          </div>
        );
      case 'EVENT_MODE_EXPIRING':
        return (
          <div className="notif-icon-box notif-icon-box--expiring" title="Chế độ sự kiện sắp hết hạn">
            <Hourglass size={16} />
          </div>
        );
      case 'EVENT_MODE_LIMIT_CHANGED':
        return (
          <div className="notif-icon-box notif-icon-box--schedule" title="Giới hạn chế độ sự kiện đã thay đổi">
            <SlidersHorizontal size={16} />
          </div>
        );
      // A-05: gửi Guard mỗi lần bật / tắt / đổi giờ kết thúc chế độ sự kiện
      case 'EVENT_MODE_CHANGED':
        return (
          <div className="notif-icon-box notif-icon-box--schedule" title="Chế độ sự kiện thay đổi">
            <ShieldCheck size={16} />
          </div>
        );
      case 'EVENT_MODE_SCHEDULED':
        return (
          <div className="notif-icon-box notif-icon-box--schedule" title="Đã đặt lịch sự kiện">
            <CalendarCheck size={16} />
          </div>
        );
      case 'EVENT_MODE_SCHEDULE_STARTING':
        return (
          <div className="notif-icon-box notif-icon-box--expiring" title="Lịch sự kiện sắp bắt đầu">
            <CalendarClock size={16} />
          </div>
        );
      case 'EVENT_MODE_SCHEDULE_FAILED':
        return (
          <div className="notif-icon-box notif-icon-box--denied" title="Lịch sự kiện thất bại">
            <CalendarX size={16} />
          </div>
        );
      // Step 5b: thông báo đổi loại khu vực (FM) và đơn bị hệ thống huỷ (người gửi + thành viên)
      case 'AREA_TYPE_CHANGED':
        return (
          <div className="notif-icon-box notif-icon-box--schedule" title="Khu vực đổi loại">
            <ArrowLeftRight size={16} />
          </div>
        );
      case 'REQUEST_SYSTEM_CANCELLED':
        return (
          <div className="notif-icon-box notif-icon-box--cancelled" title="Đơn bị hệ thống hủy">
            <XCircle size={16} />
          </div>
        );
      // B-03 (BR-RQ-46): FM chuyển đơn đã duyệt sang Hoàn thành, kèm lý do
      case 'REQUEST_FINISHED':
        return (
          <div className="notif-icon-box notif-icon-box--cancelled" title="Đơn đã được kết thúc">
            <CheckCheck size={16} />
          </div>
        );
      // Guest visits & photos notifications (U5)
      case 'GUEST_VISIT_PENDING':
        return (
          <div className="notif-icon-box notif-icon-box--pending" title="Lượt khách mới chờ duyệt">
            <UserCheck size={16} />
          </div>
        );
      case 'GUEST_VISIT_APPROVED':
        return (
          <div className="notif-icon-box notif-icon-box--approved" title="Lượt khách đã được duyệt">
            <CircleCheck size={16} />
          </div>
        );
      case 'GUEST_VISIT_REJECTED':
        return (
          <div className="notif-icon-box notif-icon-box--rejected" title="Lượt khách bị từ chối">
            <CircleX size={16} />
          </div>
        );
      case 'GUEST_VISIT_REVOKED':
        return (
          <div className="notif-icon-box notif-icon-box--denied" title="Lượt khách bị thu hồi">
            <ShieldX size={16} />
          </div>
        );
      case 'GUEST_VISIT_EXPIRED':
        return (
          <div className="notif-icon-box notif-icon-box--expiring" title="Lượt khách hết hạn">
            <Clock size={16} />
          </div>
        );
      // Step 6 (H1, H2): vô hiệu hoá khu vực
      case 'ACCESS_PERMISSION_REVOKED':
        return (
          <div className="notif-icon-box notif-icon-box--denied" title="Quyền chỉ định ra vào bị thu hồi">
            <UserMinus size={16} />
          </div>
        );
      case 'GUEST_VISIT_CANCELLED_BY_SYSTEM':
        return (
          <div className="notif-icon-box notif-icon-box--cancelled" title="Lượt khách bị hệ thống hủy">
            <CalendarOff size={16} />
          </div>
        );
      case 'GUEST_PHOTO_REQUIRED':
        return (
          <div className="notif-icon-box notif-icon-box--schedule" title="Cần gắn ảnh khách">
            <Camera size={16} />
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
      <PageHeader
        title="Thông báo"
        description="Thông báo về yêu cầu, khu vực, khách và các sự kiện liên quan đến bạn"
        actions={
          <button
            type="button"
            className="ui-btn ui-btn--secondary ui-btn--md"
            disabled={!hasUnread || loading}
            onClick={handleMarkAllAsRead}
          >
            Đánh dấu đã đọc tất cả
          </button>
        }
      />
      <div className="notif-card">

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
                    {formatDateTime(notif.createdAt)}
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
