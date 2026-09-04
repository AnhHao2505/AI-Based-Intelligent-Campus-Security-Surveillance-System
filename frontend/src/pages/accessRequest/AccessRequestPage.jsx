import React, { useState, useEffect, useCallback } from 'react';
import {
  KeyRound,
  History,
  PlusCircle,
  Clock,
  User,
  Users,
  Building,
  CheckCircle2,
  XCircle,
  AlertCircle,
  Search,
  Trash2,
  Calendar,
  X,
  RefreshCw,
  FileText
} from 'lucide-react';
import accessRequestService from '../../services/accessRequestService';
import '../../styles/AccessRequestPage.css';

export default function AccessRequestPage() {
  const [activeTab, setActiveTab] = useState('create'); // 'create' | 'history'
  
  // Available Areas
  const [areas, setAreas] = useState([]);
  const [loadingAreas, setLoadingAreas] = useState(false);

  // Form State
  const [selectedAreaId, setSelectedAreaId] = useState('');
  const [requestType, setRequestType] = useState('INDIVIDUAL');
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [purpose, setPurpose] = useState('');
  
  // Group Members state
  const [memberCodeInput, setMemberCodeInput] = useState('');
  const [memberList, setMemberList] = useState([]); // [{ userCode, fullName, email }]
  const [lookingUpMember, setLookingUpMember] = useState(false);

  // Submit & Alert state
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState(null);
  const [formSuccess, setFormSuccess] = useState(null);

  // History State
  const [historyList, setHistoryList] = useState([]);
  const [historyStatusFilter, setHistoryStatusFilter] = useState('');
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [historyPage, setHistoryPage] = useState(0);
  const [historyTotalPages, setHistoryTotalPages] = useState(1);
  const [selectedDetail, setSelectedDetail] = useState(null);

  // Helper: Default times (tomorrow 08:00 to 11:00)
  const initDefaultTimes = () => {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const yyyy = tomorrow.getFullYear();
    const mm = String(tomorrow.getMonth() + 1).padStart(2, '0');
    const dd = String(tomorrow.getDate()).padStart(2, '0');

    setStartTime(`${yyyy}-${mm}-${dd}T08:00`);
    setEndTime(`${yyyy}-${mm}-${dd}T11:00`);
  };

  // Load available areas
  const loadAreas = useCallback(async () => {
    setLoadingAreas(true);
    try {
      const data = await accessRequestService.getAvailableAreas();
      setAreas(data || []);
    } catch (err) {
      console.error('Lỗi khi tải danh sách khu vực:', err);
    } finally {
      setLoadingAreas(false);
    }
  }, []);

  // Load my requests
  const loadMyRequests = useCallback(async (page = 0, status = historyStatusFilter) => {
    setLoadingHistory(true);
    try {
      const res = await accessRequestService.getMyRequests({
        status: status || undefined,
        page,
        size: 10
      });
      setHistoryList(res?.content || []);
      setHistoryTotalPages(res?.totalPages || 1);
      setHistoryPage(page);
    } catch (err) {
      console.error('Lỗi khi tải lịch sử yêu cầu:', err);
    } finally {
      setLoadingHistory(false);
    }
  }, [historyStatusFilter]);

  useEffect(() => {
    loadAreas();
    initDefaultTimes();
  }, [loadAreas]);

  useEffect(() => {
    if (activeTab === 'history') {
      loadMyRequests(0, historyStatusFilter);
    }
  }, [activeTab, historyStatusFilter, loadMyRequests]);

  // Selected area object
  const currentArea = areas.find(a => a.id === selectedAreaId);

  // When area changes, if area is PRIVATE, force INDIVIDUAL
  const handleAreaChange = (e) => {
    const areaId = e.target.value;
    setSelectedAreaId(areaId);
    const found = areas.find(a => a.id === areaId);
    if (found && found.areaLevel === 'PRIVATE') {
      setRequestType('INDIVIDUAL');
      setMemberList([]);
    }
  };

  // Member lookup
  const handleAddMember = async () => {
    const code = memberCodeInput.trim();
    if (!code) return;

    if (memberList.some(m => m.userCode.toLowerCase() === code.toLowerCase())) {
      setFormError(`Mã người dùng ${code} đã có trong danh sách.`);
      return;
    }

    setLookingUpMember(true);
    setFormError(null);
    try {
      const user = await accessRequestService.getUserByCode(code);
      if (user) {
        setMemberList(prev => [...prev, {
          userCode: user.userCode || code,
          fullName: user.fullName || 'Người dùng',
          email: user.email || ''
        }]);
        setMemberCodeInput('');
      } else {
        setFormError(`Không tìm thấy người dùng với mã số: ${code}`);
      }
    } catch (err) {
      setFormError(err.message || `Không thể tra cứu mã người dùng ${code}`);
    } finally {
      setLookingUpMember(false);
    }
  };

  const handleRemoveMember = (code) => {
    setMemberList(prev => prev.filter(m => m.userCode !== code));
  };

  // Submit Request
  const handleSubmit = async (e) => {
    e.preventDefault();
    setFormError(null);
    setFormSuccess(null);

    if (!selectedAreaId) {
      setFormError('Vui lòng chọn khu vực cần đăng ký');
      return;
    }

    if (!startTime || !endTime) {
      setFormError('Vui lòng chọn đầy đủ thời gian bắt đầu và kết thúc');
      return;
    }

    const start = new Date(startTime);
    const end = new Date(endTime);
    if (start >= end) {
      setFormError('Thời gian bắt đầu phải trước thời gian kết thúc');
      return;
    }

    if (requestType === 'GROUP' && memberList.length === 0) {
      setFormError('Yêu cầu theo nhóm bắt buộc phải thêm ít nhất một mã số thành viên');
      return;
    }

    if (!purpose.trim()) {
      setFormError('Vui lòng nhập mục đích sử dụng khu vực');
      return;
    }

    setSubmitting(true);
    try {
      const payload = {
        areaId: selectedAreaId,
        requestType,
        startTime: start.toISOString(),
        endTime: end.toISOString(),
        purpose: purpose.trim(),
        memberUserCodes: requestType === 'GROUP' ? memberList.map(m => m.userCode) : []
      };

      await accessRequestService.createRequest(payload);
      setFormSuccess('Gửi yêu cầu truy cập thành công! Ban quản lý sẽ sớm phê duyệt.');
      
      // Reset form
      setSelectedAreaId('');
      setPurpose('');
      setMemberList([]);
      initDefaultTimes();

      // Delay then switch to history
      setTimeout(() => {
        setActiveTab('history');
      }, 1200);
    } catch (err) {
      setFormError(err.message || 'Đã có lỗi xảy ra khi tạo yêu cầu.');
    } finally {
      setSubmitting(false);
    }
  };

  // Format date helper
  const formatDateTime = (isoString) => {
    if (!isoString) return '—';
    const d = new Date(isoString);
    return d.toLocaleString('vi-VN', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  };

  return (
    <div className="arp-container">
      {/* Header */}
      <div className="arp-header">
        <div>
          <h1 className="arp-header__title">Đăng ký Yêu cầu Truy cập Khu vực</h1>
          <p className="arp-header__subtitle">
            Gửi yêu cầu xin cấp quyền truy cập tạm thời vào các khu vực riêng tư hoặc bán riêng tư trong khuôn viên campus.
          </p>
        </div>
      </div>

      {/* Tabs */}
      <div className="arp-tabs">
        <button
          className={`arp-tab ${activeTab === 'create' ? 'arp-tab--active' : ''}`}
          onClick={() => { setActiveTab('create'); setFormError(null); setFormSuccess(null); }}
        >
          <PlusCircle size={16} />
          <span>Tạo yêu cầu mới</span>
        </button>

        <button
          className={`arp-tab ${activeTab === 'history' ? 'arp-tab--active' : ''}`}
          onClick={() => setActiveTab('history')}
        >
          <History size={16} />
          <span>Lịch sử yêu cầu của tôi</span>
          {historyList.length > 0 && (
            <span className="arp-tab__badge">{historyList.length}</span>
          )}
        </button>
      </div>

      {/* TAB 1: CREATE REQUEST FORM */}
      {activeTab === 'create' && (
        <div className="arp-card">
          {formSuccess && (
            <div className="arp-banner arp-banner--success">
              <CheckCircle2 size={18} />
              <span>{formSuccess}</span>
            </div>
          )}

          {formError && (
            <div className="arp-banner arp-banner--error">
              <AlertCircle size={18} />
              <span>{formError}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="arp-form">
            {/* Area selection */}
            <div className="arp-form-group">
              <label className="arp-label">
                <Building size={14} />
                <span>Khu vực truy cập <span className="arp-required">*</span></span>
              </label>
              <select
                className="arp-select"
                value={selectedAreaId}
                onChange={handleAreaChange}
                disabled={loadingAreas || submitting}
                required
              >
                <option value="">-- Chọn khu vực (SEMI_PRIVATE hoặc PRIVATE) --</option>
                {areas.map(a => (
                  <option key={a.id} value={a.id}>
                    [{a.code}] {a.name} - {a.building || 'Chưa rõ tòa'}, Tầng {a.floor || '1'} ({a.areaLevel})
                  </option>
                ))}
              </select>
              
              {currentArea && (
                <div className={`arp-area-info ${currentArea.areaLevel === 'PRIVATE' ? 'arp-area-info--private' : 'arp-area-info--semi'}`}>
                  <AlertCircle size={14} />
                  <span>
                    Cấp độ an ninh: <strong>{currentArea.areaLevel}</strong>.
                    {currentArea.areaLevel === 'PRIVATE'
                      ? ' Khu vực bảo mật cao, chỉ áp dụng đăng ký truy cập Cá nhân (Individual).'
                      : ' Khu vực cho phép đăng ký truy cập Cá nhân hoặc Nhóm.'}
                  </span>
                </div>
              )}
            </div>

            {/* Request Type Selector */}
            <div className="arp-form-group">
              <label className="arp-label">
                <span>Hình thức đăng ký <span className="arp-required">*</span></span>
              </label>
              <div className="arp-type-grid">
                <div
                  className={`arp-type-card ${requestType === 'INDIVIDUAL' ? 'arp-type-card--selected' : ''}`}
                  onClick={() => setRequestType('INDIVIDUAL')}
                >
                  <div className="arp-type-card__icon">
                    <User size={18} />
                  </div>
                  <div>
                    <div className="arp-type-card__title">Cá nhân (Individual)</div>
                    <div className="arp-type-card__desc">
                      Đăng ký quyền ra vào khu vực cho chính tài khoản của bạn.
                    </div>
                  </div>
                </div>

                <div
                  className={`arp-type-card ${
                    requestType === 'GROUP' ? 'arp-type-card--selected' : ''
                  } ${currentArea?.areaLevel === 'PRIVATE' ? 'arp-type-card--disabled' : ''}`}
                  onClick={() => {
                    if (currentArea?.areaLevel !== 'PRIVATE') {
                      setRequestType('GROUP');
                    }
                  }}
                  title={currentArea?.areaLevel === 'PRIVATE' ? 'Khu vực PRIVATE không hỗ trợ đăng ký nhóm' : ''}
                >
                  <div className="arp-type-card__icon">
                    <Users size={18} />
                  </div>
                  <div>
                    <div className="arp-type-card__title">Tập thể / Nhóm (Group)</div>
                    <div className="arp-type-card__desc">
                      Đăng ký quyền ra vào cho một nhóm thành viên theo danh sách mã số.
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* Time Grid */}
            <div className="arp-form-grid">
              <div className="arp-form-group">
                <label className="arp-label">
                  <Clock size={14} />
                  <span>Thời gian bắt đầu <span className="arp-required">*</span></span>
                </label>
                <input
                  type="datetime-local"
                  className="arp-input"
                  value={startTime}
                  onChange={(e) => setStartTime(e.target.value)}
                  disabled={submitting}
                  required
                />
              </div>

              <div className="arp-form-group">
                <label className="arp-label">
                  <Clock size={14} />
                  <span>Thời gian kết thúc <span className="arp-required">*</span></span>
                </label>
                <input
                  type="datetime-local"
                  className="arp-input"
                  value={endTime}
                  onChange={(e) => setEndTime(e.target.value)}
                  disabled={submitting}
                  required
                />
              </div>
            </div>

            {/* Group Members Section (if GROUP) */}
            {requestType === 'GROUP' && (
              <div className="arp-form-group">
                <label className="arp-label">
                  <Users size={14} />
                  <span>Danh sách mã số thành viên nhóm <span className="arp-required">*</span></span>
                </label>
                <div className="arp-member-lookup">
                  <input
                    type="text"
                    className="arp-input"
                    placeholder="Nhập mã số thành viên (vd: SE160001, NV102...)"
                    value={memberCodeInput}
                    onChange={(e) => setMemberCodeInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault();
                        handleAddMember();
                      }
                    }}
                    disabled={lookingUpMember || submitting}
                  />
                  <button
                    type="button"
                    className="arp-btn arp-btn--secondary"
                    onClick={handleAddMember}
                    disabled={!memberCodeInput.trim() || lookingUpMember}
                  >
                    {lookingUpMember ? 'Đang tìm...' : 'Thêm thành viên'}
                  </button>
                </div>
                <div className="arp-hint">
                  Hệ thống sẽ xác thực mã số với dữ liệu người dùng trong hệ thống campus.
                </div>

                {memberList.length > 0 && (
                  <div className="arp-member-list">
                    {memberList.map((m) => (
                      <span key={m.userCode} className="arp-member-chip">
                        <span><strong>{m.userCode}</strong> - {m.fullName}</span>
                        <button
                          type="button"
                          className="arp-member-chip__remove"
                          onClick={() => handleRemoveMember(m.userCode)}
                          title="Xóa thành viên"
                        >
                          <X size={14} />
                        </button>
                      </span>
                    ))}
                  </div>
                )}
              </div>
            )}

            {/* Purpose */}
            <div className="arp-form-group arp-form-group--full">
              <label className="arp-label">
                <FileText size={14} />
                <span>Mục đích sử dụng khu vực <span className="arp-required">*</span></span>
              </label>
              <textarea
                className="arp-textarea"
                rows={4}
                placeholder="Mô tả cụ thể mục đích truy cập (ví dụ: Họp nhóm đồ án Capstone, nghiên cứu phòng Lab Robotics, chuẩn bị sự kiện...)"
                value={purpose}
                onChange={(e) => setPurpose(e.target.value)}
                maxLength={1000}
                disabled={submitting}
                required
              />
              <div className="arp-hint" style={{ textAlign: 'right' }}>
                {purpose.length}/1000 ký tự
              </div>
            </div>

            {/* Actions */}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem' }}>
              <button
                type="submit"
                className="arp-btn arp-btn--primary"
                disabled={submitting}
              >
                <KeyRound size={16} />
                <span>{submitting ? 'Đang gửi yêu cầu...' : 'Gửi yêu cầu truy cập'}</span>
              </button>
            </div>
          </form>
        </div>
      )}

      {/* TAB 2: HISTORY */}
      {activeTab === 'history' && (
        <div className="arp-card">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem', flexWrap: 'wrap', gap: '0.75rem' }}>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              {[
                { label: 'Tất cả', val: '' },
                { label: 'Chờ duyệt', val: 'PENDING' },
                { label: 'Đã duyệt', val: 'APPROVED' },
                { label: 'Bị từ chối', val: 'REJECTED' }
              ].map(f => (
                <button
                  key={f.val}
                  type="button"
                  className={`arp-btn ${historyStatusFilter === f.val ? 'arp-btn--primary' : 'arp-btn--secondary'}`}
                  style={{ padding: '0.4rem 0.85rem', fontSize: '0.8125rem' }}
                  onClick={() => setHistoryStatusFilter(f.val)}
                >
                  {f.label}
                </button>
              ))}
            </div>

            <button
              type="button"
              className="arp-btn arp-btn--secondary"
              style={{ padding: '0.4rem 0.85rem' }}
              onClick={() => loadMyRequests(historyPage, historyStatusFilter)}
              title="Làm mới danh sách"
            >
              <RefreshCw size={14} className={loadingHistory ? 'spin' : ''} />
              <span>Làm mới</span>
            </button>
          </div>

          {loadingHistory ? (
            <div className="arp-empty">
              <RefreshCw size={24} className="spin" style={{ marginBottom: '0.5rem' }} />
              <div>Đang tải danh sách yêu cầu...</div>
            </div>
          ) : historyList.length === 0 ? (
            <div className="arp-empty">
              <Calendar size={32} className="arp-empty__icon" />
              <div className="arp-empty__text">Chưa có yêu cầu truy cập nào</div>
            </div>
          ) : (
            <div className="arp-table-container">
              <table className="arp-table">
                <thead>
                  <tr>
                    <th>Khu vực</th>
                    <th>Hình thức</th>
                    <th>Thời gian truy cập</th>
                    <th>Trạng thái</th>
                    <th>Ngày tạo</th>
                    <th style={{ textAlign: 'right' }}>Thao tác</th>
                  </tr>
                </thead>
                <tbody>
                  {historyList.map(req => (
                    <tr key={req.id}>
                      <td>
                        <div style={{ fontWeight: 600 }}>{req.areaName}</div>
                        <div style={{ fontSize: '0.75rem', color: 'var(--theme-text-muted)' }}>
                          [{req.areaCode}] - {req.building || 'Campus'} - Tầng {req.floor || '1'}
                        </div>
                      </td>
                      <td>
                        <span className={`arp-badge ${req.requestType === 'GROUP' ? 'arp-badge--group' : 'arp-badge--individual'}`}>
                          {req.requestType === 'GROUP' ? 'Nhóm' : 'Cá nhân'}
                        </span>
                      </td>
                      <td>
                        <div style={{ fontSize: '0.8125rem' }}>{formatDateTime(req.startTime)}</div>
                        <div style={{ fontSize: '0.75rem', color: 'var(--theme-text-muted)' }}>
                          đến {formatDateTime(req.endTime)}
                        </div>
                      </td>
                      <td>
                        <span className={`arp-badge arp-badge--${req.status.toLowerCase()}`}>
                          {req.status === 'PENDING' && 'Chờ duyệt'}
                          {req.status === 'APPROVED' && 'Đã duyệt'}
                          {req.status === 'REJECTED' && 'Từ chối'}
                        </span>
                      </td>
                      <td style={{ fontSize: '0.8125rem', color: 'var(--theme-text-muted)' }}>
                        {formatDateTime(req.createdAt)}
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <button
                          type="button"
                          className="arp-btn arp-btn--secondary"
                          style={{ padding: '0.35rem 0.65rem', fontSize: '0.8125rem' }}
                          onClick={() => setSelectedDetail(req)}
                        >
                          Chi tiết
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* DETAIL MODAL */}
      {selectedDetail && (
        <div className="arp-modal-overlay" onClick={() => setSelectedDetail(null)}>
          <div className="arp-modal" onClick={e => e.stopPropagation()}>
            <div className="arp-modal__header">
              <h2 className="arp-modal__title">Chi tiết Yêu cầu Truy cập</h2>
              <button
                type="button"
                className="arp-modal__close"
                onClick={() => setSelectedDetail(null)}
              >
                <X size={18} />
              </button>
            </div>

            <div className="arp-modal__body">
              <div className="arp-detail-grid">
                <div className="arp-detail-item">
                  <span className="arp-detail-label">Khu vực</span>
                  <span className="arp-detail-val">{selectedDetail.areaName} ({selectedDetail.areaCode})</span>
                  <span style={{ fontSize: '0.75rem', color: 'var(--theme-text-muted)' }}>
                    Cấp độ: {selectedDetail.areaLevel} | {selectedDetail.building} - Tầng {selectedDetail.floor}
                  </span>
                </div>

                <div className="arp-detail-item">
                  <span className="arp-detail-label">Trạng thái</span>
                  <div>
                    <span className={`arp-badge arp-badge--${selectedDetail.status.toLowerCase()}`}>
                      {selectedDetail.status === 'PENDING' && 'Chờ phê duyệt'}
                      {selectedDetail.status === 'APPROVED' && 'Đã phê duyệt'}
                      {selectedDetail.status === 'REJECTED' && 'Bị từ chối'}
                    </span>
                  </div>
                </div>

                <div className="arp-detail-item">
                  <span className="arp-detail-label">Thời gian bắt đầu</span>
                  <span className="arp-detail-val">{formatDateTime(selectedDetail.startTime)}</span>
                </div>

                <div className="arp-detail-item">
                  <span className="arp-detail-label">Thời gian kết thúc</span>
                  <span className="arp-detail-val">{formatDateTime(selectedDetail.endTime)}</span>
                </div>

                <div className="arp-detail-item">
                  <span className="arp-detail-label">Hình thức</span>
                  <span className="arp-detail-val">{selectedDetail.requestType === 'GROUP' ? 'Tập thể / Nhóm' : 'Cá nhân'}</span>
                </div>

                <div className="arp-detail-item">
                  <span className="arp-detail-label">Ngày gửi yêu cầu</span>
                  <span className="arp-detail-val">{formatDateTime(selectedDetail.createdAt)}</span>
                </div>
              </div>

              {/* Purpose */}
              <div className="arp-detail-item">
                <span className="arp-detail-label">Mục đích sử dụng</span>
                <div className="arp-detail-box">
                  {selectedDetail.purpose}
                </div>
              </div>

              {/* Members (if group) */}
              {selectedDetail.requestType === 'GROUP' && selectedDetail.members && selectedDetail.members.length > 0 && (
                <div className="arp-detail-item">
                  <span className="arp-detail-label">Danh sách thành viên nhóm ({selectedDetail.members.length})</span>
                  <div className="arp-member-list">
                    {selectedDetail.members.map(m => (
                      <span key={m.userId || m.userCode} className="arp-member-chip">
                        <strong>{m.userCode}</strong> - {m.fullName}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {/* Rejection reason (if rejected) */}
              {selectedDetail.status === 'REJECTED' && selectedDetail.rejectionReason && (
                <div className="arp-detail-item">
                  <span className="arp-detail-label" style={{ color: 'var(--theme-danger)' }}>Lý do từ chối</span>
                  <div className="arp-detail-box" style={{ borderColor: 'var(--theme-danger-border)', background: 'var(--theme-danger-bg)', color: 'var(--theme-danger-text)' }}>
                    {selectedDetail.rejectionReason}
                  </div>
                </div>
              )}

              {/* Reviewer info */}
              {selectedDetail.reviewedAt && (
                <div className="arp-detail-item">
                  <span className="arp-detail-label">Thông tin duyệt</span>
                  <div style={{ fontSize: '0.8125rem', color: 'var(--theme-text-secondary)' }}>
                    Duyệt bởi: <strong>{selectedDetail.reviewerName || 'Quản lý cơ sở'}</strong> vào lúc {formatDateTime(selectedDetail.reviewedAt)}
                  </div>
                </div>
              )}
            </div>

            <div className="arp-modal__footer">
              <button
                type="button"
                className="arp-btn arp-btn--secondary"
                onClick={() => setSelectedDetail(null)}
              >
                Đóng
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
