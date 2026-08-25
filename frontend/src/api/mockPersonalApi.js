// Mock Data & API Layer for Personal Portal (Student / Teacher / Staff)
// Technical Debt Note: access_requests & notifications tables will be added to DB schema in future migration.

const MOCK_ACCESS_HISTORY = [
  {
    id: 'AH-101',
    dateTime: '21/05/2026 14:30:12',
    area: 'Library Area',
    result: 'VALID', // 'VALID' | 'UNAUTHORIZED'
    resultLabel: 'Valid Access',
    note: '-',
  },
  {
    id: 'AH-102',
    dateTime: '21/05/2026 09:15:45',
    area: 'Server Room',
    result: 'UNAUTHORIZED',
    resultLabel: 'Unauthorized',
    note: 'Permission Expired',
  },
  {
    id: 'AH-103',
    dateTime: '20/05/2026 16:45:00',
    area: 'Lab AI (Lab 402)',
    result: 'VALID',
    resultLabel: 'Valid Access',
    note: '-',
  },
  {
    id: 'AH-104',
    dateTime: '19/05/2026 08:00:10',
    area: 'Main Gate',
    result: 'VALID',
    resultLabel: 'Valid Access',
    note: '-',
  },
];

const INITIAL_NOTIFICATIONS = [
  {
    id: 'NOTIF-1',
    type: 'WARNING', // 'WARNING' | 'DENIED' | 'APPROVED' | 'REJECTED'
    title: 'Permission expiring soon',
    description: 'Your access to Lab AI will expire on May 30, 2026. Contact Admin if you need renewal.',
    timestamp: '2 hours ago',
    isUnread: true,
  },
  {
    id: 'NOTIF-2',
    type: 'DENIED',
    title: 'Access attempt denied',
    description: 'A denied access attempt was recorded at Server Room using your identity. If this wasn\'t you, contact Security immediately.',
    timestamp: 'Yesterday, 09:15',
    isUnread: true,
  },
  {
    id: 'NOTIF-3',
    type: 'APPROVED',
    title: 'Access request approved',
    description: 'Your request for Library Area (extended hours) has been approved by Admin.',
    timestamp: 'May 18, 2026',
    isUnread: false,
  },
  {
    id: 'NOTIF-4',
    type: 'REJECTED',
    title: 'Access request rejected',
    description: 'Your request for Data Center was rejected. Tap to see the reason.',
    timestamp: 'May 12, 2026',
    isUnread: false,
  },
];

const INITIAL_REQUESTS = [
  {
    id: 'REQ-01',
    area: 'Data Center',
    dateSubmitted: 'May 20, 2026',
    status: 'PENDING', // 'PENDING' | 'APPROVED' | 'REJECTED'
    details: 'Waiting for review',
    adminNote: null,
  },
  {
    id: 'REQ-02',
    area: 'Library Area (extended hours)',
    dateSubmitted: 'May 18, 2026',
    status: 'APPROVED',
    details: 'Valid until Jun 30, 2026',
    adminNote: null,
  },
  {
    id: 'REQ-03',
    area: 'Data Center',
    dateSubmitted: 'May 12, 2026',
    status: 'REJECTED',
    details: 'View reason',
    adminNote: 'Data Center requires Facility Manager approval and a valid safety training certificate. Please contact the Facility Office directly.',
  },
];

// Helper to get stored items
const getStorageItem = (key, defaultVal) => {
  try {
    const item = localStorage.getItem(key);
    return item ? JSON.parse(item) : defaultVal;
  } catch {
    return defaultVal;
  }
};

const setStorageItem = (key, val) => {
  try {
    localStorage.setItem(key, JSON.stringify(val));
  } catch (e) {
    console.error('Failed to set localStorage', e);
  }
};

export const getAccessHistory = () => {
  return MOCK_ACCESS_HISTORY;
};

export const getNotifications = () => {
  return getStorageItem('sep_personal_notifs', INITIAL_NOTIFICATIONS);
};

export const markAllNotificationsAsRead = () => {
  const current = getNotifications();
  const updated = current.map((n) => ({ ...n, isUnread: false }));
  setStorageItem('sep_personal_notifs', updated);
  return updated;
};

export const getMyRequests = () => {
  return getStorageItem('sep_personal_requests', INITIAL_REQUESTS);
};

export const createAccessRequest = (area, reason) => {
  const current = getMyRequests();
  const newReq = {
    id: `REQ-${Date.now().toString().slice(-4)}`,
    area: area,
    dateSubmitted: new Date().toLocaleDateString('en-US', { month: 'short', day: '2-digit', year: 'numeric' }),
    status: 'PENDING',
    details: 'Waiting for review',
    adminNote: null,
    reason: reason,
  };
  const updated = [newReq, ...current];
  setStorageItem('sep_personal_requests', updated);
  return updated;
};
