import React, { useState, useRef, useEffect } from 'react';
import {
  Shield,
  ShieldAlert,
  ShieldCheck,
  Upload,
  Download,
  Trash2,
  UserPlus,
  Search,
  Users,
  Camera,
  Sliders,
  X,
  FileSpreadsheet,
  CheckCircle2,
  AlertCircle,
  Clock,
  Key,
  Layers,
  ChevronRight,
  Info
} from 'lucide-react';
import './EditZoneModal.css';

const DEFAULT_ROLES = [
  { id: 'ADMIN', label: 'Quản trị viên (Admin)', color: 'purple' },
  { id: 'FACILITY_MANAGER', label: 'Quản lý CSVC (FM)', color: 'blue' },
  { id: 'INTERNAL_GUARD', label: 'Bảo vệ Nội bộ (Security)', color: 'amber' },
  { id: 'OUTSOURCED_GUARD', label: 'Bảo vệ Thuê ngoài', color: 'orange' },
  { id: 'LECTURER', label: 'Giảng viên (Lecturer)', color: 'emerald' },
  { id: 'STUDENT', label: 'Sinh viên FPTU (Student)', color: 'cyan' },
  { id: 'STAFF', label: 'Cán bộ / Nhân viên', color: 'slate' }
];

const SECURITY_LEVELS = [
  {
    level: 'Level 1',
    code: 'LEVEL_1',
    name: 'Level 1 (Public / Công cộng)',
    badge: 'Công cộng',
    color: 'green',
    description: 'Khu vực mở cho tất cả sinh viên, giảng viên, nhân viên và khách viếng thăm.',
    example: 'Main Lobby, Library, Canteen, Corridors'
  },
  {
    level: 'Level 2',
    code: 'LEVEL_2',
    name: 'Level 2 (Semi-Private / Hạn chế)',
    badge: 'Hạn chế',
    color: 'amber',
    description: 'Yêu cầu quyền hạn hợp lệ. Cảnh báo khi phát hiện người lạ chưa đăng ký.',
    example: 'AI Labs, Hardware Labs, Staff & Faculty Offices'
  },
  {
    level: 'Level 3',
    code: 'LEVEL_3',
    name: 'Level 3 (Private / Tối mật & Nghiêm ngặt)',
    badge: 'Tối mật',
    color: 'red',
    description: 'Khu vực an ninh cao nhất. Nghiêm cấm mọi truy cập không có trong Whitelist.',
    example: 'Server Room B204, Principal Office, Electrical Substation'
  }
];

export default function EditZoneModal({
  isOpen,
  onClose,
  zoneData,
  onSave
}) {
  const [activeTab, setActiveTab] = useState('policy'); // 'policy' | 'whitelist' | 'cameras'
  const [zoneForm, setZoneForm] = useState({
    id: '',
    name: '',
    code: '',
    floor: 'Floor 1',
    level: 'Level 3',
    levelCode: 'LEVEL_3',
    status: 'ACTIVE',
    escalationPolicy: 'INTERNAL_ONLY_AUTO_ESCALATE',
    preferredGuard: 'INTERNAL_ONLY',
    slaEscalate: '60',
    authorizedRoles: ['ADMIN', 'FACILITY_MANAGER'],
    requireFaceMatch: true,
    minConfidence: 85,
    cameraList: ['CAM-01 (Cửa chính)', 'CAM-02 (Tủ Rack Server)']
  });

  const [assignedUsers, setAssignedUsers] = useState([]);
  const [newUser, setNewUser] = useState({
    id: '',
    name: '',
    role: 'LECTURER',
    email: '',
    schedule: '24/7 Unlimited'
  });

  const [searchQuery, setSearchQuery] = useState('');
  const [roleFilter, setRoleFilter] = useState('ALL');
  const [dragOver, setDragOver] = useState(false);
  const [importStatus, setImportStatus] = useState(null); // { type: 'success'|'error', message: string }
  const fileInputRef = useRef(null);

  useEffect(() => {
    if (zoneData) {
      const isRed = zoneData.level?.includes('3') || zoneData.color === 'red';
      const isAmber = zoneData.level?.includes('2') || zoneData.color === 'amber';
      const currentLevelCode = isRed ? 'LEVEL_3' : isAmber ? 'LEVEL_2' : 'LEVEL_1';

      setZoneForm({
        id: zoneData.id || 'server-room',
        name: zoneData.name || 'Server Room',
        code: zoneData.code || `ZN-${(zoneData.id || 'ZONE').toUpperCase()}`,
        floor: zoneData.floor || 'Floor 1',
        level: isRed ? 'Level 3 (Private)' : isAmber ? 'Level 2 (Semi-Private)' : 'Level 1 (Public)',
        levelCode: currentLevelCode,
        status: zoneData.status || 'ACTIVE',
        escalationPolicy: zoneData.escalationPolicy || 'INTERNAL_ONLY_AUTO_ESCALATE',
        preferredGuard: zoneData.preferredGuard || 'INTERNAL_ONLY',
        slaEscalate: zoneData.slaEscalate ? String(zoneData.slaEscalate).replace(/\D/g, '') || '60' : '60',
        authorizedRoles: Array.isArray(zoneData.authorizedRoles)
          ? zoneData.authorizedRoles
          : typeof zoneData.authorizedRoles === 'string'
            ? zoneData.authorizedRoles.split(',').map((r) => r.trim()).filter(Boolean)
            : ['ADMIN', 'FACILITY_MANAGER'],
        requireFaceMatch: true,
        minConfidence: 85,
        cameraList: zoneData.cameraList || ['CAM-01 (Lối vào)', 'CAM-02 (Khu vực trung tâm)']
      });

      // Sample initial assigned users for realistic feel if empty
      if (zoneData.assignedUsers && zoneData.assignedUsers.length > 0) {
        setAssignedUsers(zoneData.assignedUsers);
      } else if (zoneData.id === 'server-room' || isRed) {
        setAssignedUsers([
          {
            id: 'AD-001',
            name: 'Quản Trị Viên FPTU',
            role: 'ADMIN',
            email: 'admin@fpt.edu.vn',
            schedule: '24/7 Unlimited'
          },
          {
            id: 'FM-001',
            name: 'Trần Bình (Quản Lý CSVC)',
            role: 'FACILITY_MANAGER',
            email: 'manager.binh@fpt.edu.vn',
            schedule: '24/7 Unlimited'
          },
          {
            id: 'SEC-001',
            name: 'Nguyễn Văn An (Bảo Vệ)',
            role: 'INTERNAL_GUARD',
            email: 'guard.an@fpt.edu.vn',
            schedule: 'Shift Hours (06:00 - 22:00)'
          }
        ]);
      } else {
        setAssignedUsers([
          {
            id: 'GV-001',
            name: 'TS. Thân Thị Ngọc Vân',
            role: 'LECTURER',
            email: 'vanttn@fpt.edu.vn',
            schedule: 'Working Hours (07:00 - 18:00)'
          },
          {
            id: 'SE170123',
            name: 'Nguyễn Tiến Đạt',
            role: 'STUDENT',
            email: 'datntse170123@fpt.edu.vn',
            schedule: 'Lab Access (08:00 - 17:00)'
          }
        ]);
      }
      setImportStatus(null);
    }
  }, [zoneData, isOpen]);

  if (!isOpen) return null;

  const handleLevelSelect = (lvl) => {
    setZoneForm((prev) => ({
      ...prev,
      level: lvl.name,
      levelCode: lvl.code
    }));
  };

  const handleRoleToggle = (roleId) => {
    setZoneForm((prev) => {
      const exists = prev.authorizedRoles.includes(roleId);
      const nextRoles = exists
        ? prev.authorizedRoles.filter((r) => r !== roleId)
        : [...prev.authorizedRoles, roleId];
      return { ...prev, authorizedRoles: nextRoles };
    });
  };

  const handleAddSingleUser = async (e) => {
    e.preventDefault();
    if (!newUser.id.trim()) {
      setImportStatus({
        type: 'error',
        message: 'Vui lòng nhập Mã người dùng (MSSV / MSNV).'
      });
      return;
    }

    try {
      const response = await fetch(`http://localhost:8080/api/users/${newUser.id.trim()}`);
      if (!response.ok) {
        if (response.status === 404) {
          setImportStatus({
            type: 'error',
            message: 'User not found'
          });
        } else {
          setImportStatus({
            type: 'error',
            message: 'Có lỗi xảy ra khi tìm kiếm người dùng.'
          });
        }
        return;
      }

      const userData = await response.json();

      const createdUser = {
        id: userData.userCode || userData.id || newUser.id.trim(),
        name: userData.fullName || 'Người dùng FPTU',
        role: userData.role || 'STAFF',
        email: userData.email || '',
        schedule: '24/7 Unlimited'
      };

      setAssignedUsers((prev) => [createdUser, ...prev]);
      setNewUser({
        id: '',
        name: '',
        role: 'LECTURER',
        email: '',
        schedule: '24/7 Unlimited'
      });
      setImportStatus({
        type: 'success',
        message: `Đã thêm thành công người dùng "${createdUser.name}" (${createdUser.id}).`
      });
    } catch (error) {
      setImportStatus({
        type: 'error',
        message: 'Lỗi kết nối máy chủ.'
      });
    }
  };

  const handleDeleteUser = (userId) => {
    setAssignedUsers((prev) => prev.filter((u) => u.id !== userId));
  };

  const handleClearAllUsers = () => {
    if (window.confirm('Bạn có chắc chắn muốn xóa toàn bộ danh sách phân quyền của khu vực này?')) {
      setAssignedUsers([]);
      setImportStatus({
        type: 'info',
        message: 'Đã làm trống danh sách người dùng được cấp quyền.'
      });
    }
  };

  // PARSE IMPORTED FILE (CSV, TSV, TXT, JSON)
  const processUploadedFile = (file) => {
    if (!file) return;

    const fileName = file.name;
    const reader = new FileReader();

    reader.onload = (event) => {
      try {
        const text = event.target.result;
        let parsedUsers = [];

        if (fileName.endsWith('.json')) {
          const jsonData = JSON.parse(text);
          const rawList = Array.isArray(jsonData) ? jsonData : jsonData.users || [];
          parsedUsers = rawList.map((item, idx) => ({
            id: item.id || item.user_id || item.code || item.mssv || item.msnv || `USR-${idx + 1}`,
            name: item.name || item.full_name || item.ho_ten || 'User FPTU',
            role: (item.role || 'STAFF').toUpperCase(),
            email: item.email || '',
            schedule: item.schedule || '24/7 Unlimited'
          }));
        } else {
          // CSV / TSV / TXT parsing
          const lines = text.split(/\r\n|\n/).map((l) => l.trim()).filter(Boolean);
          if (lines.length === 0) {
            throw new Error('File tải lên rỗng.');
          }

          // Detect delimiter
          const firstLine = lines[0];
          let delimiter = ',';
          if (firstLine.includes('\t')) delimiter = '\t';
          else if (firstLine.includes(';')) delimiter = ';';
          else if (firstLine.includes('|')) delimiter = '|';

          // Check if first row is header
          const hasHeader =
            firstLine.toLowerCase().includes('name') ||
            firstLine.toLowerCase().includes('id') ||
            firstLine.toLowerCase().includes('mssv') ||
            firstLine.toLowerCase().includes('role') ||
            firstLine.toLowerCase().includes('họ tên') ||
            firstLine.toLowerCase().includes('email');

          const dataRows = hasHeader ? lines.slice(1) : lines;

          parsedUsers = dataRows.map((row, idx) => {
            // Split and clean quotes
            const cols = row.split(delimiter).map((c) => c.replace(/^["']|["']$/g, '').trim());
            return {
              id: cols[0] || `USR-${Date.now().toString().slice(-4)}-${idx + 1}`,
              name: cols[1] || `Người dùng ${idx + 1}`,
              role: (cols[2] || 'STAFF').toUpperCase(),
              email: cols[3] || '',
              schedule: cols[4] || '24/7 Unlimited'
            };
          });
        }

        if (parsedUsers.length === 0) {
          throw new Error('Không trích xuất được người dùng nào từ file.');
        }

        // Merge with existing users avoiding direct duplicate IDs
        setAssignedUsers((prev) => {
          const existingIds = new Set(prev.map((u) => u.id));
          const newEntries = parsedUsers.filter((u) => !existingIds.has(u.id));
          return [...newEntries, ...prev];
        });

        setImportStatus({
          type: 'success',
          message: `🎉 Tải lên thành công! Đã nhập ${parsedUsers.length} người dùng từ file "${fileName}".`
        });
      } catch (err) {
        console.error('File parsing error:', err);
        setImportStatus({
          type: 'error',
          message: `Lỗi đọc file: ${err.message || 'Định dạng file không hợp lệ.'}`
        });
      }
    };

    reader.onerror = () => {
      setImportStatus({
        type: 'error',
        message: 'Có lỗi xảy ra khi đọc file trên máy tính của bạn.'
      });
    };

    reader.readAsText(file);
  };

  const handleFileInputChange = (e) => {
    const file = e.target.files?.[0];
    if (file) {
      processUploadedFile(file);
    }
    // Reset file input value so user can upload the same file again if desired
    e.target.value = '';
  };

  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (file) {
      processUploadedFile(file);
    }
  };

  const handleDownloadTemplate = () => {
    const csvContent =
      'user_id,full_name,role,email,schedule\n' +
      'SE170123,Nguyen Van A,STUDENT,anvse170123@fpt.edu.vn,Working Hours (07:00 - 18:00)\n' +
      'GV-0042,Tran Thi B,LECTURER,binhtt@fpt.edu.vn,24/7 Unlimited\n' +
      'SEC-001,Nguyen Van An,INTERNAL_GUARD,guard.an@fpt.edu.vn,Shift Hours (06:00 - 22:00)\n' +
      'FM-001,Tran Binh,FACILITY_MANAGER,manager.binh@fpt.edu.vn,24/7 Unlimited\n' +
      'CB-0109,Le Hoang Nam,STAFF,namlh@fpt.edu.vn,Working Hours (08:00 - 17:30)';

    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.setAttribute('href', url);
    link.setAttribute('download', `template_zone_authorized_users_${zoneForm.code || 'config'}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const handleSaveModal = (e) => {
    e.preventDefault();
    const finalData = {
      ...zoneForm,
      assignedUsers,
      totalAssignedCount: assignedUsers.length
    };
    onSave(finalData);
  };

  // Filtered users for search
  const filteredUsers = assignedUsers.filter((user) => {
    const matchSearch =
      user.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      user.id.toLowerCase().includes(searchQuery.toLowerCase()) ||
      (user.email && user.email.toLowerCase().includes(searchQuery.toLowerCase()));

    const matchRole = roleFilter === 'ALL' || user.role === roleFilter;
    return matchSearch && matchRole;
  });

  const getRoleBadgeClass = (role) => {
    switch (role) {
      case 'ADMIN':
        return 'role-badge--purple';
      case 'FACILITY_MANAGER':
        return 'role-badge--blue';
      case 'INTERNAL_GUARD':
        return 'role-badge--amber';
      case 'OUTSOURCED_GUARD':
        return 'role-badge--orange';
      case 'LECTURER':
        return 'role-badge--emerald';
      case 'STUDENT':
        return 'role-badge--cyan';
      default:
        return 'role-badge--slate';
    }
  };

  const currentLevelInfo =
    SECURITY_LEVELS.find((l) => l.code === zoneForm.levelCode) || SECURITY_LEVELS[2];

  return (
    <div className="edit-zone-modal-backdrop" onClick={onClose}>
      <div
        className="edit-zone-modal"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        {/* MODAL HEADER */}
        <div className="edit-zone-modal__header">
          <div className="edit-zone-modal__header-left">
            <div className={`ez-badge-level ez-badge-level--${currentLevelInfo.color}`}>
              <Shield className="ez-icon" size={16} />
              <span>{currentLevelInfo.badge}</span>
            </div>
            <div>
              <div className="ez-header-title-row">
                <h3 className="ez-header-title">{zoneForm.name || 'Zone Configuration'}</h3>
                <span className="ez-code-pill">{zoneForm.code}</span>
              </div>
              <p className="ez-header-sub">
                Campus FPTU Tân Uyên • {zoneForm.floor} • Hệ thống Giám sát An ninh Thông minh (FA26SE040)
              </p>
            </div>
          </div>

          <div className="edit-zone-modal__header-right">
            <button
              type="button"
              className="edit-zone-modal__close-btn"
              onClick={onClose}
              aria-label="Close"
            >
              <X size={20} />
            </button>
          </div>
        </div>

        {/* TAB NAVIGATION */}
        <div className="ez-tabs-bar">
          <button
            type="button"
            className={`ez-tab-btn ${activeTab === 'policy' ? 'ez-tab-btn--active' : ''}`}
            onClick={() => setActiveTab('policy')}
          >
            <Sliders size={16} />
            <span>1. Chính sách & Cấp độ Bảo mật</span>
          </button>
          <button
            type="button"
            className={`ez-tab-btn ${activeTab === 'whitelist' ? 'ez-tab-btn--active' : ''}`}
            onClick={() => setActiveTab('whitelist')}
          >
            <Users size={16} />
            <span>2. Danh sách Phân quyền & Whitelist</span>
            <span className="ez-tab-badge">{assignedUsers.length}</span>
          </button>
          <button
            type="button"
            className={`ez-tab-btn ${activeTab === 'cameras' ? 'ez-tab-btn--active' : ''}`}
            onClick={() => setActiveTab('cameras')}
          >
            <Camera size={16} />
            <span>3. Camera & AI Giám sát</span>
          </button>
        </div>

        {/* STATUS BANNER */}
        {importStatus && (
          <div className={`ez-alert-banner ez-alert-banner--${importStatus.type}`}>
            {importStatus.type === 'success' ? (
              <CheckCircle2 size={18} />
            ) : (
              <AlertCircle size={18} />
            )}
            <span>{importStatus.message}</span>
            <button
              type="button"
              className="ez-alert-close"
              onClick={() => setImportStatus(null)}
            >
              ×
            </button>
          </div>
        )}

        {/* MODAL BODY */}
        <div className="edit-zone-modal__body-wrap">
          {/* TAB 1: POLICY & SECURITY CONFIGURATION */}
          {activeTab === 'policy' && (
            <div className="ez-tab-content ez-tab-content--policy">
              {/* Left Column: Basic Info & Security Level Cards */}
              <div className="ez-policy-col">
                <div className="ez-section-card">
                  <div className="ez-card-head">
                    <Layers size={16} className="ez-card-icon text-cyan-400" />
                    <h4>Thông tin Khu vực (Zone Identity)</h4>
                  </div>

                  <div className="ez-grid-2">
                    <div className="ez-form-group">
                      <label>Tên Khu vực (Zone Name)</label>
                      <input
                        type="text"
                        value={zoneForm.name}
                        onChange={(e) => setZoneForm({ ...zoneForm, name: e.target.value })}
                        placeholder="VD: Server Room B204"
                      />
                    </div>
                    <div className="ez-form-group">
                      <label>Mã Khu vực (Zone Code)</label>
                      <input
                        type="text"
                        value={zoneForm.code}
                        onChange={(e) => setZoneForm({ ...zoneForm, code: e.target.value })}
                        placeholder="VD: ZN-SRV-01"
                      />
                    </div>
                  </div>
                </div>

                <div className="ez-section-card">
                  <div className="ez-card-head">
                    <ShieldCheck size={16} className="ez-card-icon text-emerald-400" />
                    <h4>Cấp độ Bảo mật (Security Level)</h4>
                  </div>

                  <div className="ez-level-selector-grid">
                    {SECURITY_LEVELS.map((lvl) => {
                      const isSelected = zoneForm.levelCode === lvl.code;
                      return (
                        <div
                          key={lvl.code}
                          className={`ez-level-card ez-level-card--${lvl.color} ${isSelected ? 'ez-level-card--selected' : ''
                            }`}
                          onClick={() => handleLevelSelect(lvl)}
                        >
                          <div className="ez-level-card__header">
                            <span className="ez-level-card__tag">{lvl.badge}</span>
                            {isSelected && <span className="ez-level-card__active-dot">● Chọn</span>}
                          </div>
                          <h5 className="ez-level-card__title">{lvl.name}</h5>
                          <p className="ez-level-card__desc">{lvl.description}</p>
                          <div className="ez-level-card__footer">Ví dụ: {lvl.example}</div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              </div>

              {/* Right Column: Escalation & Access Roles */}
              <div className="ez-policy-col">
                <div className="ez-section-card">
                  <div className="ez-card-head">
                    <Clock size={16} className="ez-card-icon text-amber-400" />
                    <h4>Chính sách Phản hồi & Leo thang (Escalation Policy)</h4>
                  </div>

                  <div className="ez-form-group">
                    <label>Quy trình Leo thang khi có đột nhập / bất thường</label>
                    <select
                      value={zoneForm.escalationPolicy}
                      onChange={(e) => setZoneForm({ ...zoneForm, escalationPolicy: e.target.value })}
                    >
                      <option value="INTERNAL_ONLY_AUTO_ESCALATE">
                        🛡️ Bảo vệ Nội bộ xử lý trước ➔ Tự động leo thang Quản lý CSVC (Manager) sau SLA
                      </option>
                      <option value="BROADCAST_ALL_GUARDS">
                        📢 Báo động trực tiếp toàn bộ Bảo vệ trực ca (Internal & Outsourced)
                      </option>
                      <option value="SILENT_ADMIN_ALERT">
                        🚨 Báo động khẩn cấp gửi trực tiếp Ban Quản trị & Security Leader
                      </option>
                    </select>
                  </div>

                  <div className="ez-grid-2 mt-3">
                    <div className="ez-form-group">
                      <label>Lực lượng Bảo vệ ưu tiên</label>
                      <select
                        value={zoneForm.preferredGuard}
                        onChange={(e) => setZoneForm({ ...zoneForm, preferredGuard: e.target.value })}
                      >
                        <option value="INTERNAL_ONLY">Chỉ Bảo vệ Nội bộ FPTU</option>
                        <option value="FACILITY_MANAGER">Quản lý Cơ sở vật chất</option>
                        <option value="ALL_SECURITY">Tất cả lực lượng an ninh</option>
                      </select>
                    </div>

                    <div className="ez-form-group">
                      <label>Thời gian chờ SLA trước khi leo thang</label>
                      <div className="ez-sla-input-row">
                        <input
                          type="number"
                          value={zoneForm.slaEscalate}
                          onChange={(e) => setZoneForm({ ...zoneForm, slaEscalate: e.target.value })}
                          min="10"
                          max="600"
                        />
                        <span className="ez-input-unit">Giây (s)</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div className="ez-section-card">
                  <div className="ez-card-head">
                    <Key size={16} className="ez-card-icon text-purple-400" />
                    <h4>Vai trò chung được phép truy cập (Authorized Global Roles)</h4>
                  </div>
                  <p className="ez-help-text">
                    Người dùng thuộc các vai trò được chọn bên dưới sẽ mặc định được cấp quyền đi vào khu vực này.
                  </p>

                  <div className="ez-roles-chip-grid">
                    {DEFAULT_ROLES.map((r) => {
                      const active = zoneForm.authorizedRoles.includes(r.id);
                      return (
                        <button
                          key={r.id}
                          type="button"
                          className={`ez-role-toggle-chip ez-role-toggle-chip--${r.color} ${active ? 'ez-role-toggle-chip--active' : ''
                            }`}
                          onClick={() => handleRoleToggle(r.id)}
                        >
                          <span className="ez-chip-check">{active ? '✓' : '+'}</span>
                          <span>{r.label}</span>
                        </button>
                      );
                    })}
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* TAB 2: WHITELIST & FILE IMPORT */}
          {activeTab === 'whitelist' && (
            <div className="ez-tab-content ez-tab-content--whitelist">
              {/* Top Controls & File Upload Box */}
              <div className="ez-whitelist-top">
                {/* Drag & Drop Upload Zone */}
                <div
                  className={`ez-dropzone ${dragOver ? 'ez-dropzone--dragover' : ''}`}
                  onDragOver={(e) => {
                    e.preventDefault();
                    setDragOver(true);
                  }}
                  onDragLeave={() => setDragOver(false)}
                  onDrop={handleDrop}
                  onClick={() => fileInputRef.current?.click()}
                >
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept=".csv, .tsv, .txt, .json, .xlsx, .xls"
                    className="hidden"
                    style={{ display: 'none' }}
                    onChange={handleFileInputChange}
                  />

                  <div className="ez-dropzone__content">
                    <div className="ez-dropzone__icon-circle">
                      <Upload size={24} className="ez-dropzone__icon" />
                    </div>
                    <div>
                      <h5 className="ez-dropzone__title">
                        Nhấn để Tải lên File hoặc Kéo & Thả file vào đây
                      </h5>
                      <p className="ez-dropzone__desc">
                        Hỗ trợ file <strong>.CSV</strong>, <strong>.TXT</strong>, <strong>.JSON</strong>, <strong>.TSV</strong> (Tự động nhận diện MSSV/MSNV, Họ tên, Vai trò)
                      </p>
                    </div>
                  </div>

                  <div className="ez-dropzone__actions" onClick={(e) => e.stopPropagation()}>
                    <button
                      type="button"
                      className="ez-btn-action ez-btn-action--upload"
                      onClick={() => fileInputRef.current?.click()}
                    >
                      <FileSpreadsheet size={15} />
                      <span>Chọn file từ máy tính</span>
                    </button>
                    <button
                      type="button"
                      className="ez-btn-action ez-btn-action--template"
                      onClick={handleDownloadTemplate}
                      title="Tải file mẫu CSV"
                    >
                      <Download size={15} />
                      <span>Tải file CSV mẫu</span>
                    </button>
                  </div>
                </div>

                {/* Quick Add Single User Form */}
                <div className="ez-quick-add-card">
                  <div className="ez-card-head">
                    <UserPlus size={16} className="text-cyan-400" />
                    <h4>Thêm nhanh từng người dùng (Add One by One)</h4>
                  </div>

                  <form className="ez-quick-add-form" onSubmit={handleAddSingleUser}>
                    <div className="ez-form-group">
                      <label>Mã NV / MSSV</label>
                      <input
                        type="text"
                        placeholder="VD: SE171904"
                        value={newUser.id}
                        onChange={(e) => setNewUser({ ...newUser, id: e.target.value })}
                      />
                    </div>

                    <button type="submit" className="ez-btn-add-user">
                      <UserPlus size={16} />
                      <span>Thêm vào danh sách</span>
                    </button>
                  </form>
                </div>
              </div>

              {/* Whitelist Users Table & Search */}
              <div className="ez-whitelist-table-section">
                <div className="ez-table-toolbar">
                  <div className="ez-search-box">
                    <Search size={16} className="ez-search-icon" />
                    <input
                      type="text"
                      placeholder="Tìm kiếm theo Tên, MSSV/MSNV hoặc Email..."
                      value={searchQuery}
                      onChange={(e) => setSearchQuery(e.target.value)}
                    />
                    {searchQuery && (
                      <button
                        type="button"
                        className="ez-search-clear"
                        onClick={() => setSearchQuery('')}
                      >
                        ×
                      </button>
                    )}
                  </div>

                  <div className="ez-toolbar-filters">
                    <select
                      value={roleFilter}
                      onChange={(e) => setRoleFilter(e.target.value)}
                      className="ez-role-filter-select"
                    >
                      <option value="ALL">Tất cả vai trò ({assignedUsers.length})</option>
                      {DEFAULT_ROLES.map((r) => (
                        <option key={r.id} value={r.id}>
                          {r.label}
                        </option>
                      ))}
                    </select>

                    {assignedUsers.length > 0 && (
                      <button
                        type="button"
                        className="ez-btn-clear-all"
                        onClick={handleClearAllUsers}
                      >
                        <Trash2 size={14} />
                        <span>Xóa tất cả</span>
                      </button>
                    )}
                  </div>
                </div>

                <div className="ez-table-container">
                  <table className="ez-table">
                    <thead>
                      <tr>
                        <th style={{ width: '30%' }}>Người dùng & Email</th>
                        <th style={{ width: '20%' }}>Mã NV / MSSV</th>
                        <th style={{ width: '22%' }}>Vai trò</th>
                        <th style={{ width: '20%' }}>Khung giờ truy cập</th>
                        <th style={{ width: '8%', textAlign: 'center' }}>Thao tác</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredUsers.length === 0 ? (
                        <tr>
                          <td colSpan="5" className="ez-table__empty">
                            <div className="ez-empty-state">
                              <Users size={36} className="ez-empty-icon" />
                              <p className="ez-empty-text">
                                {searchQuery || roleFilter !== 'ALL'
                                  ? 'Không tìm thấy người dùng phù hợp với bộ lọc.'
                                  : 'Chưa có người dùng nào được chỉ định trong danh sách whitelist này.'}
                              </p>
                              <button
                                type="button"
                                className="ez-empty-btn"
                                onClick={() => fileInputRef.current?.click()}
                              >
                                <Upload size={14} />
                                <span>Import file danh sách người dùng</span>
                              </button>
                            </div>
                          </td>
                        </tr>
                      ) : (
                        filteredUsers.map((user) => {
                          const initials = user.name
                            ? user.name
                              .split(' ')
                              .map((n) => n[0])
                              .slice(-2)
                              .join('')
                              .toUpperCase()
                            : 'US';

                          return (
                            <tr key={user.id}>
                              <td>
                                <div className="ez-user-cell">
                                  <div className="ez-avatar">{initials}</div>
                                  <div className="ez-user-info">
                                    <span className="ez-user-name">{user.name}</span>
                                    <span className="ez-user-email">
                                      {user.email || `${user.id.toLowerCase()}@fpt.edu.vn`}
                                    </span>
                                  </div>
                                </div>
                              </td>
                              <td>
                                <span className="ez-user-code-pill">{user.id}</span>
                              </td>
                              <td>
                                <span className={`ez-role-badge ${getRoleBadgeClass(user.role)}`}>
                                  {user.role}
                                </span>
                              </td>
                              <td>
                                <div className="ez-schedule-cell">
                                  <Clock size={13} className="text-slate-400" />
                                  <span>{user.schedule || '24/7'}</span>
                                </div>
                              </td>
                              <td style={{ textAlign: 'center' }}>
                                <button
                                  type="button"
                                  className="ez-row-delete-btn"
                                  onClick={() => handleDeleteUser(user.id)}
                                  title="Xóa người dùng"
                                >
                                  <Trash2 size={15} />
                                </button>
                              </td>
                            </tr>
                          );
                        })
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          {/* TAB 3: CAMERA & AI STREAMING */}
          {activeTab === 'cameras' && (
            <div className="ez-tab-content ez-tab-content--cameras">
              <div className="ez-section-card">
                <div className="ez-card-head">
                  <Camera size={16} className="ez-card-icon text-cyan-400" />
                  <h4>Camera Giám sát liên kết với Khu vực này (Linked RTSP Streams)</h4>
                </div>
                <p className="ez-help-text">
                  Dữ liệu video từ các Camera này sẽ được AI Microservice (YOLO11 + InsightFace) phân tích theo thời gian thực (&lt; 3 giây).
                </p>

                <div className="ez-camera-list-grid">
                  <div className="ez-camera-item ez-camera-item--active">
                    <div className="ez-camera-item__top">
                      <span className="ez-camera-badge">CAM-SRV-01</span>
                      <span className="ez-stream-status ez-stream-status--online">● LIVE RTSP</span>
                    </div>
                    <h5 className="ez-camera-title">Camera Lối vào Cửa chính Server Room</h5>
                    <p className="ez-camera-sub">RTSP://10.10.20.101:554/live/srv_entrance • 15 FPS</p>
                  </div>

                  <div className="ez-camera-item ez-camera-item--active">
                    <div className="ez-camera-item__top">
                      <span className="ez-camera-badge">CAM-SRV-02</span>
                      <span className="ez-stream-status ez-stream-status--online">● LIVE RTSP</span>
                    </div>
                    <h5 className="ez-camera-title">Camera Toàn cảnh Tủ Rack Server & UPS</h5>
                    <p className="ez-camera-sub">RTSP://10.10.20.102:554/live/srv_rack_a • 15 FPS</p>
                  </div>
                </div>
              </div>

              <div className="ez-section-card mt-4">
                <div className="ez-card-head">
                  <ShieldAlert size={16} className="ez-card-icon text-amber-400" />
                  <h4>Cấu hình Nhận diện Khuôn mặt AI (Face Matching Engine)</h4>
                </div>

                <div className="ez-grid-2">
                  <div className="ez-form-group">
                    <label>Ngưỡng tin cậy nhận diện khuôn mặt (Cosine Threshold)</label>
                    <div className="ez-slider-row">
                      <input
                        type="range"
                        min="50"
                        max="98"
                        value={zoneForm.minConfidence}
                        onChange={(e) =>
                          setZoneForm({ ...zoneForm, minConfidence: Number(e.target.value) })
                        }
                      />
                      <span className="ez-slider-val">{zoneForm.minConfidence}%</span>
                    </div>
                  </div>

                  <div className="ez-form-group">
                    <label>Cảnh báo Đột nhập tức thì (Real-time Latency)</label>
                    <div className="ez-switch-row">
                      <input
                        type="checkbox"
                        id="requireFaceMatch"
                        checked={zoneForm.requireFaceMatch}
                        onChange={(e) =>
                          setZoneForm({ ...zoneForm, requireFaceMatch: e.target.checked })
                        }
                      />
                      <label htmlFor="requireFaceMatch" className="ez-checkbox-label">
                        Tự động tạo Incident & phát chuông báo khi nhận diện người lạ (&lt; 3s)
                      </label>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* MODAL FOOTER */}
        <div className="edit-zone-modal__footer">
          <div className="ez-footer-hint">
            <Info size={15} className="text-cyan-400" />
            <span>Cấu hình và danh sách phân quyền sẽ được cập nhật đồng bộ sang AI Service & Postgres.</span>
          </div>

          <div className="ez-footer-buttons">
            <button type="button" className="ez-btn-cancel" onClick={onClose}>
              Hủy bỏ (Cancel)
            </button>
            <button type="button" className="ez-btn-save" onClick={handleSaveModal}>
              <CheckCircle2 size={16} />
              <span>Lưu Cấu hình Khu vực</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
