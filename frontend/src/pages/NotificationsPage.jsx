import { useState } from 'react';
import { Clock, ShieldAlert, CheckCircle, XCircle } from 'lucide-react';
import { getNotifications, markAllNotificationsAsRead } from '../api/mockPersonalApi';
import './NotificationsPage.css';

export default function NotificationsPage() {
  const [notifications, setNotifications] = useState(getNotifications());

  const handleMarkAllRead = () => {
    const updated = markAllNotificationsAsRead();
    setNotifications(updated);
  };

  const getNotifIcon = (type) => {
    switch (type) {
      case 'WARNING':
        return (
          <div className="notif-icon-box notif-icon-box--warning">
            <Clock size={18} />
          </div>
        );
      case 'DENIED':
        return (
          <div className="notif-icon-box notif-icon-box--denied">
            <ShieldAlert size={18} />
          </div>
        );
      case 'APPROVED':
        return (
          <div className="notif-icon-box notif-icon-box--approved">
            <CheckCircle size={18} />
          </div>
        );
      case 'REJECTED':
      default:
        return (
          <div className="notif-icon-box notif-icon-box--rejected">
            <XCircle size={18} />
          </div>
        );
    }
  };

  return (
    <div className="notifications-card">
      <div className="notifications-header">
        <h2>Notifications</h2>
        <button className="mark-read-btn" onClick={handleMarkAllRead}>
          Mark all as read
        </button>
      </div>

      <div className="notifications-list">
        {notifications.map((item) => (
          <div
            key={item.id}
            className={`notification-item ${item.isUnread ? 'notification-item--unread' : ''}`}
          >
            <div className="notif-left">
              {getNotifIcon(item.type)}
              <div className="notif-content">
                <h4>
                  <span>{item.title}</span>
                  {item.isUnread && <span className="unread-dot" />}
                </h4>
                <p>{item.description}</p>
              </div>
            </div>
            <div className="notif-timestamp">{item.timestamp}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
