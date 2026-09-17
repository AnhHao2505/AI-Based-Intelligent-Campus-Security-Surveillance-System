import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  KeyRound,
  Clock,
  User,
  Users,
  CheckCircle2,
  XCircle,
  AlertCircle,
  AlertTriangle,
  ShieldAlert,
  X,
  RefreshCw,
  Send,
  Inbox,
  ChevronLeft,
  ChevronRight,
  Ban
} from 'lucide-react';
import Modal from '../../components/ui/Modal';
import Button from '../../components/ui/Button';
import accessRequestService from '../../services/accessRequestService';
import '../../styles/AccessRequestPage.css';

export default function AccessRequestPage() {
  // Available Areas
  const [areas, setAreas] = useState([]);
  const [loadingAreas, setLoadingAreas] = useState(false);

  // Form State
  const [selectedAreaId, setSelectedAreaId] = useState('');
  const [requestType, setRequestType] = useState('INDIVIDUAL'); // 'INDIVIDUAL' | 'GROUP'
  const [requestDate, setRequestDate] = useState('');
  const [startHour, setStartHour] = useState('08:00');
  const [endHour, setEndHour] = useState('11:00');
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
  const [historyTotalElements, setHistoryTotalElements] = useState(0);
  const [selectedDetail, setSelectedDetail] = useState(null);

  // Expandable Rejection Reason rows in table (Set of request IDs)
  const [expandedRejectIds, setExpandedRejectIds] = useState(new Set());

  // Cancel Request state
  const [cancelItem, setCancelItem] = useState(null);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState(null);
  const [historyWarning, setHistoryWarning] = useState(null);

  // Ref to scroll down to history card upon successful submission
  const historyCardRef = useRef(null);

  // Helper: Default times (tomorrow 08:00 to 11:00)
  const initDefaultTimes = () => {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const yyyy = tomorrow.getFullYear();
    const mm = String(tomorrow.getMonth() + 1).padStart(2, '0');
    const dd = String(tomorrow.getDate()).padStart(2, '0');

    setRequestDate(`${yyyy}-${mm}-${dd}`);
    setStartHour('08:00');
    setEndHour('11:00');
  };

  // Check if chosen time range is valid
  const isTimeValid = () => {
    if (!requestDate || !startHour || !endHour) return false;
    const [sh, sm] = startHour.split(':').map(Number);
    const [eh, em] = endHour.split(':').map(Number);
    const startMin = sh * 60 + sm;
    const endMin = eh * 60 + em;
    return endMin > startMin;
  };

  // Real-time summary text for date & time
  const getTimeSummary = () => {
    if (!requestDate || !startHour || !endHour) {
      return { isError: false, text: '' };
    }
    const [sh, sm] = startHour.split(':').map(Number);
    const [eh, em] = endHour.split(':').map(Number);
    const startMin = sh * 60 + sm;
    const endMin = eh * 60 + em;

    if (endMin <= startMin) {
      return {
        isError: true,
        text: 'Giờ kết thúc phải sau giờ bắt đầu'
      };
    }

    const diffMin = endMin - startMin;
    if (diffMin > 12 * 60) {
      return {
        isError: true,
        text: 'Thời lượng truy cập tối đa không quá 12 giờ'
      };
    }

    const startDateTime = new Date(`${requestDate}T${startHour}:00`);
    const nowBuffer = new Date(Date.now() - 5 * 60 * 1000);
    if (startDateTime < nowBuffer) {
      return {
        isError: true,
        text: 'Thời gian bắt đầu không được ở trong quá khứ'
      };
    }

    const maxAdvance = new Date();
    maxAdvance.setDate(maxAdvance.getDate() + 30);
    if (startDateTime > maxAdvance) {
      return {
        isError: true,
        text: 'Thời gian bắt đầu không được vượt quá 30 ngày tới'
      };
    }

    const hours = Math.floor(diffMin / 60);
    const mins = diffMin % 60;
    let durationStr = '';
    if (hours > 0 && mins > 0) {
      durationStr = `${hours} tiếng ${mins} phút`;
    } else if (hours > 0) {
      durationStr = `${hours} tiếng`;
    } else {
      durationStr = `${mins} phút`;
    }

    const [y, m, d] = requestDate.split('-').map(Number);
    const dateObj = new Date(y, m - 1, d);
    const daysOfWeek = ['Chủ Nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];
    const dayName = daysOfWeek[dateObj.getDay()] || '';
    const formattedDate = `${String(d).padStart(2, '0')}/${String(m).padStart(2, '0')}/${y}`;

    return {
      isError: false,
      text: `${dayName}, ${formattedDate} · ${startHour} – ${endHour} (${durationStr})`
    };
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
      setHistoryTotalElements(res?.totalElements || 0);
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
    loadMyRequests(0, historyStatusFilter);
  }, [historyStatusFilter, loadMyRequests]);

  // Selected area object
  const currentArea = areas.find((a) => a.id === selectedAreaId);

  // When area changes, if area is PRIVATE, force INDIVIDUAL
  const handleAreaChange = (e) => {
    const areaId = e.target.value;
    setSelectedAreaId(areaId);
    const found = areas.find((a) => a.id === areaId);
    if (found && found.areaLevel === 'PRIVATE') {
      setRequestType('INDIVIDUAL');
      setMemberList([]);
    }
  };

  // Member lookup
  const handleAddMember = async () => {
    const code = memberCodeInput.trim();
    if (!code) return;

    if (memberList.some((m) => m.userCode.toLowerCase() === code.toLowerCase())) {
      setFormError(`Mã người dùng ${code} đã có trong danh sách.`);
      return;
    }

    setLookingUpMember(true);
    setFormError(null);
    try {
      const user = await accessRequestService.getUserByCode(code);
      if (user) {
        setMemberList((prev) => [
          ...prev,
          {
            userCode: user.userCode || code,
            fullName: user.fullName || 'Người dùng',
            email: user.email || ''
          }
        ]);
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
    setMemberList((prev) => prev.filter((m) => m.userCode !== code));
  };

  // Toggle inline rejection reason in table
  const toggleRejectReason = (id) => {
    setExpandedRejectIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
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

    if (!requestDate || !startHour || !endHour) {
      setFormError('Vui lòng chọn đầy đủ ngày và khung thời gian');
      return;
    }

    if (!isTimeValid()) {
      setFormError('Thời gian kết thúc phải sau thời gian bắt đầu');
      return;
    }

    const start = new Date(`${requestDate}T${startHour}:00`);
    const end = new Date(`${requestDate}T${endHour}:00`);
    const nowBuffer = new Date(Date.now() - 5 * 60 * 1000);

    if (start < nowBuffer) {
      setFormError('Thời gian bắt đầu không được ở trong quá khứ.');
      return;
    }

    const diffMs = end.getTime() - start.getTime();
    if (diffMs > 12 * 60 * 60 * 1000) {
      setFormError('Thời lượng truy cập tối đa không quá 12 giờ.');
      return;
    }

    const maxAdvance = new Date();
    maxAdvance.setDate(maxAdvance.getDate() + 30);
    if (start > maxAdvance) {
      setFormError('Thời gian bắt đầu không được vượt quá 30 ngày tới.');
      return;
    }

    let cleanMemberCodes = [];
    if (requestType === 'GROUP') {
      if (currentArea?.areaLevel === 'PRIVATE') {
        setFormError('Khu vực riêng tư (PRIVATE) chỉ cho phép đăng ký cá nhân.');
        return;
      }

      if (memberList.length === 0) {
        setFormError('Yêu cầu theo nhóm bắt buộc phải thêm ít nhất một mã số thành viên');
        return;
      }

      // Deduplicate and silently exclude requester
      const currentUser = JSON.parse(localStorage.getItem('user') || '{}');
      const myCode = (currentUser.userCode || '').trim().toLowerCase();
      const seen = new Set();
      for (const m of memberList) {
        const code = (m.userCode || '').trim();
        const lower = code.toLowerCase();
        if (lower && lower !== myCode && !seen.has(lower)) {
          seen.add(lower);
          cleanMemberCodes.push(code);
        }
      }

      if (cleanMemberCodes.length === 0) {
        setFormError('Yêu cầu theo nhóm bắt buộc phải có ít nhất một thành viên khác ngoài người tạo.');
        return;
      }

      if (cleanMemberCodes.length > 30) {
        setFormError('Số lượng thành viên trong nhóm tối đa 30 người (không tính người tạo).');
        return;
      }
    }

    if (!purpose.trim()) {
      setFormError('Vui lòng nhập mục đích sử dụng khu vực');
      return;
    }

    setSubmitting(true);
    try {
      if (requestType === 'GROUP') {
        await accessRequestService.createGroupRequest({
          areaId: selectedAreaId,
          startTime: start.toISOString(),
          endTime: end.toISOString(),
          purpose: purpose.trim(),
          memberUserCodes: cleanMemberCodes
        });
      } else {
        await accessRequestService.createIndividualRequest({
          areaId: selectedAreaId,
          startTime: start.toISOString(),
          endTime: end.toISOString(),
          purpose: purpose.trim()
        });
      }

      setFormSuccess('Gửi yêu cầu truy cập thành công! Ban quản lý sẽ sớm xem xét phê duyệt.');

      // Reset form
      setSelectedAreaId('');
      setPurpose('');
      setMemberList([]);
      initDefaultTimes();

      // Refresh history list and smooth scroll down
      loadMyRequests(0, historyStatusFilter);
      setTimeout(() => {
        historyCardRef.current?.scrollIntoView({ behavior: 'smooth' });
      }, 500);
    } catch (err) {
      setFormError(err.message || 'Đã có lỗi xảy ra khi tạo yêu cầu.');
    } finally {
      setSubmitting(false);
    }
  };

  // Confirm cancel request
  const handleConfirmCancel = async () => {
    if (!cancelItem) return;
    setCancelling(true);
    setCancelError(null);
    try {
      await accessRequestService.cancelRequest(cancelItem.id);
      setCancelItem(null);
      setFormSuccess('Huỷ yêu cầu truy cập thành công!');
      loadMyRequests(historyPage, historyStatusFilter);
      setTimeout(() => setFormSuccess(null), 4000);
    } catch (err) {
      if (err.status === 409) {
        setCancelItem(null);
        setHistoryWarning(err.message || 'Yêu cầu này vừa được xử lý, không thể huỷ.');
        loadMyRequests(historyPage, historyStatusFilter);
        setTimeout(() => setHistoryWarning(null), 7000);
      } else {
        setCancelError(err.message || 'Không thể huỷ yêu cầu truy cập.');
      }
    } finally {
      setCancelling(false);
    }
  };

  // Format table time: dd/MM/yyyy · HH:mm – HH:mm
  const formatTableTime = (startStr, endStr) => {
    if (!startStr || !endStr) return '—';
    const s = new Date(startStr);
    const e = new Date(endStr);
    const pad = (n) => String(n).padStart(2, '0');
    const dStr = `${pad(s.getDate())}/${pad(s.getMonth() + 1)}/${s.getFullYear()}`;
    const sTime = `${pad(s.getHours())}:${pad(s.getMinutes())}`;
    const eTime = `${pad(e.getHours())}:${pad(e.getMinutes())}`;

    const endDStr = `${pad(e.getDate())}/${pad(e.getMonth() + 1)}/${e.getFullYear()}`;
    if (dStr === endDStr) {
      return `${dStr} · ${sTime} – ${eTime}`;
    }
    return `${dStr} ${sTime} – ${endDStr} ${eTime}`;
  };

  // Format single datetime
  const formatDateTime = (isoString) => {
    if (!isoString) return '—';
    const d = new Date(isoString);
    const pad = (n) => String(n).padStart(2, '0');
    return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} · ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };

  const timeSummary = getTimeSummary();
  const isFormValid = Boolean(
    selectedAreaId &&
    requestDate &&
    startHour &&
    endHour &&
    isTimeValid() &&
    purpose.trim() &&
    (requestType !== 'GROUP' || memberList.length > 0)
  );

  return (
    <div className="arp-container">
      {/* THẺ 1: YÊU CẦU TRUY CẬP MỚI */}
      <div className="arp-card">
        {/* 2a. Đầu thẻ */}
        <div className="arp-card__header">
          <div className="arp-card__header-left">
            <div className="arp-card__icon-box">
              <KeyRound size={16} />
            </div>
            <div>
              <h2 className="arp-card__title">Yêu cầu truy cập mới</h2>
              <p className="arp-card__subtitle">
                Yêu cầu sẽ được Ban quản lý xem xét trước khi phê duyệt
              </p>
            </div>
          </div>
        </div>

        {/* 2d. Banner thành công / lỗi */}
        {formSuccess && (
          <div className="arp-banner arp-banner--success" style={{ margin: '14px 20px 0 20px' }}>
            <CheckCircle2 size={16} />
            <span>{formSuccess}</span>
          </div>
        )}

        {formError && (
          <div className="arp-banner arp-banner--error" style={{ margin: '14px 20px 0 20px' }}>
            <AlertCircle size={16} />
            <span>{formError}</span>
          </div>
        )}

        {/* 2b. Thân thẻ */}
        <form onSubmit={handleSubmit} className="arp-card__body">
          {/* TRƯỜNG 1: Khu vực cần truy cập */}
          <div className="arp-form-group">
            <label className="arp-label">
              <span>Khu vực cần truy cập</span>
              <span className="arp-required">*</span>
            </label>
            <select
              className="arp-select"
              value={selectedAreaId}
              onChange={handleAreaChange}
              disabled={loadingAreas || submitting}
              required
            >
              <option value="">-- Chọn khu vực (SEMI_PRIVATE hoặc PRIVATE) --</option>
              {areas.map((a) => (
                <option key={a.id} value={a.id}>
                  [{a.code}] {a.name} — {a.building || 'Campus'}, Tầng {a.floor || '1'} ({a.areaLevel})
                </option>
              ))}
            </select>

            {currentArea && (
              <div
                className={`arp-area-info ${
                  currentArea.areaLevel === 'PRIVATE' ? 'arp-area-info--private' : 'arp-area-info--semi'
                }`}
              >
                {currentArea.areaLevel === 'PRIVATE' ? (
                  <ShieldAlert size={16} style={{ flexShrink: 0 }} />
                ) : (
                  <AlertTriangle size={16} style={{ flexShrink: 0 }} />
                )}
                <span>
                  Cấp độ an ninh: <strong>{currentArea.areaLevel}</strong>.
                  {currentArea.areaLevel === 'PRIVATE'
                    ? ' Khu vực bảo mật cao, chỉ áp dụng đăng ký truy cập Cá nhân (Individual).'
                    : ' Khu vực cho phép đăng ký truy cập Cá nhân hoặc Nhóm.'}
                </span>
              </div>
            )}
          </div>

          {/* TRƯỜNG 2: Hình thức đăng ký */}
          <div className="arp-form-group">
            <label className="arp-label">
              <span>Hình thức đăng ký</span>
              <span className="arp-required">*</span>
            </label>
            <div className="arp-type-grid">
              <button
                type="button"
                className={`arp-type-btn ${requestType === 'INDIVIDUAL' ? 'arp-type-btn--selected' : ''}`}
                onClick={() => setRequestType('INDIVIDUAL')}
                disabled={submitting}
              >
                <User size={18} className="arp-type-btn__icon" />
                <div className="arp-type-btn__name">Cá nhân (Individual)</div>
                <div className="arp-type-btn__desc">
                  Đăng ký quyền ra vào khu vực cho chính tài khoản của bạn.
                </div>
              </button>

              <button
                type="button"
                className={`arp-type-btn ${requestType === 'GROUP' ? 'arp-type-btn--selected' : ''}`}
                onClick={() => {
                  if (currentArea?.areaLevel !== 'PRIVATE') {
                    setRequestType('GROUP');
                  }
                }}
                disabled={currentArea?.areaLevel === 'PRIVATE' || submitting}
                title={
                  currentArea?.areaLevel === 'PRIVATE'
                    ? 'Khu vực riêng tư chỉ cho phép đăng ký cá nhân'
                    : ''
                }
              >
                <Users size={18} className="arp-type-btn__icon" />
                <div className="arp-type-btn__name">Tập thể / Nhóm (Group)</div>
                <div className="arp-type-btn__desc">
                  Đăng ký quyền ra vào cho một nhóm thành viên theo danh sách mã số.
                </div>
              </button>
            </div>
          </div>

          {/* TRƯỜNG 3: Khung thời gian */}
          <div className="arp-form-group">
            <label className="arp-label">
              <span>Khung thời gian truy cập</span>
              <span className="arp-required">*</span>
            </label>
            <div className="arp-time-grid">
              <div>
                <label className="arp-sub-label">Ngày</label>
                <input
                  type="date"
                  className="arp-input"
                  value={requestDate}
                  onChange={(e) => setRequestDate(e.target.value)}
                  disabled={submitting}
                  required
                />
              </div>
              <div>
                <label className="arp-sub-label">Từ giờ</label>
                <input
                  type="time"
                  className="arp-input"
                  value={startHour}
                  onChange={(e) => setStartHour(e.target.value)}
                  disabled={submitting}
                  required
                />
              </div>
              <div>
                <label className="arp-sub-label">Đến giờ</label>
                <input
                  type="time"
                  className="arp-input"
                  value={endHour}
                  onChange={(e) => setEndHour(e.target.value)}
                  disabled={submitting}
                  required
                />
              </div>
            </div>

            {timeSummary.text && (
              <div
                className={`arp-time-summary ${timeSummary.isError ? 'arp-time-summary--error' : ''}`}
              >
                {timeSummary.text}
              </div>
            )}
          </div>

          {/* TRƯỜNG 4: Danh sách thành viên (nếu chọn GROUP) */}
          {requestType === 'GROUP' && (
            <div className="arp-form-group">
              <label className="arp-label">
                <span>Danh sách mã số thành viên nhóm</span>
                <span className="arp-required">*</span>
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
                  disabled={!memberCodeInput.trim() || lookingUpMember || submitting}
                >
                  {lookingUpMember ? 'Đang tra...' : 'Thêm'}
                </button>
              </div>

              {memberList.length > 0 && (
                <div className="arp-member-list">
                  {memberList.map((m) => (
                    <span key={m.userCode} className="arp-member-chip">
                      <span>{m.userCode} · {m.fullName}</span>
                      <button
                        type="button"
                        className="arp-member-chip__remove"
                        onClick={() => handleRemoveMember(m.userCode)}
                        title="Xóa thành viên"
                      >
                        <X size={13} />
                      </button>
                    </span>
                  ))}
                </div>
              )}

              <div className="arp-hint">
                {memberList.length > 0
                  ? `Đã thêm ${memberList.length} thành viên`
                  : 'Bấm Enter hoặc nút Thêm để xác thực mã số thành viên'}
              </div>
            </div>
          )}

          {/* TRƯỜNG 5: Mục đích sử dụng */}
          <div className="arp-form-group">
            <label className="arp-label">
              <span>Mục đích sử dụng khu vực</span>
              <span className="arp-required">*</span>
            </label>
            <textarea
              className="arp-textarea"
              rows={3}
              placeholder="Mô tả cụ thể mục đích truy cập (ví dụ: Họp nhóm đồ án Capstone, nghiên cứu phòng Lab Robotics, chuẩn bị sự kiện...)"
              value={purpose}
              onChange={(e) => setPurpose(e.target.value)}
              maxLength={1000}
              disabled={submitting}
              required
            />
            <div
              className="arp-hint"
              style={{
                textAlign: 'right',
                color: purpose.length > 1000 ? 'var(--theme-danger)' : 'var(--theme-text-muted)'
              }}
            >
              {purpose.length}/1000
            </div>
          </div>

          {/* 2c. Chân thẻ */}
          <div className="arp-card__footer">
            <button
              type="submit"
              className="arp-btn-submit"
              disabled={!isFormValid || submitting}
            >
              {submitting ? (
                <>
                  <RefreshCw size={15} className="arp-spin" />
                  <span>Đang gửi...</span>
                </>
              ) : (
                <>
                  <Send size={15} />
                  <span>Gửi yêu cầu</span>
                </>
              )}
            </button>
          </div>
        </form>
      </div>

      {/* THẺ 2: YÊU CẦU CỦA TÔI */}
      <div className="arp-card" ref={historyCardRef}>
        {/* 3a. Đầu thẻ */}
        <div className="arp-card__header">
          <div className="arp-card__header-left">
            <h2 className="arp-card__title">Yêu cầu của tôi</h2>
          </div>

          <div className="arp-filter-group">
            {[
              { label: 'Tất cả', val: '' },
              { label: 'Chờ duyệt', val: 'PENDING' },
              { label: 'Đã duyệt', val: 'APPROVED' },
              { label: 'Bị từ chối', val: 'REJECTED' },
              { label: 'Đã huỷ', val: 'CANCELLED' },
              { label: 'Hết hạn', val: 'EXPIRED' }
            ].map((f) => (
              <button
                key={f.val}
                type="button"
                className={`arp-filter-btn ${historyStatusFilter === f.val ? 'arp-filter-btn--active' : ''}`}
                onClick={() => setHistoryStatusFilter(f.val)}
              >
                {f.label}
              </button>
            ))}

            <button
              type="button"
              className="arp-refresh-btn"
              onClick={() => loadMyRequests(historyPage, historyStatusFilter)}
              title="Làm mới danh sách"
              disabled={loadingHistory}
            >
              <RefreshCw size={13} className={loadingHistory ? 'arp-spin' : ''} />
            </button>
          </div>
        </div>

        {historyWarning && (
          <div className="arp-banner arp-banner--warning" style={{ margin: '14px 20px 0 20px' }}>
            <AlertTriangle size={16} />
            <span>{historyWarning}</span>
          </div>
        )}

        {/* 3b. Bảng & 3d. Bảng rỗng */}
        <div className="arp-card__table-wrapper">
          {loadingHistory ? (
            <div className="arp-empty">
              <RefreshCw size={24} className="arp-spin" style={{ marginBottom: '8px' }} />
              <div className="arp-empty__text">Đang tải danh sách yêu cầu...</div>
            </div>
          ) : historyList.length === 0 ? (
            <div className="arp-empty">
              <Inbox size={28} className="arp-empty__icon" />
              <div className="arp-empty__text">Chưa có yêu cầu nào</div>
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
                  {historyList.map((req) => (
                    <React.Fragment key={req.id}>
                      <tr>
                        <td>
                          <div style={{ fontWeight: 600 }}>{req.areaName}</div>
                          <div className="arp-table-room-code">
                            [{req.areaCode}] {req.building ? `· ${req.building}` : ''} {req.floor ? `Tầng ${req.floor}` : ''}
                          </div>
                        </td>
                        <td>
                          <span
                            className={`arp-badge ${
                              req.requestType === 'GROUP' ? 'arp-badge--group' : 'arp-badge--individual'
                            }`}
                          >
                            {req.requestType === 'GROUP' ? 'Nhóm' : 'Cá nhân'}
                          </span>
                        </td>
                        <td>
                          <div style={{ fontSize: '13px' }}>
                            {formatTableTime(req.startTime, req.endTime)}
                          </div>
                        </td>
                        <td>
                          <span className={`arp-status-badge arp-status-badge--${req.status.toLowerCase()}`}>
                            {req.status === 'PENDING' && (
                              <>
                                <Clock size={11} />
                                <span>Chờ duyệt</span>
                              </>
                            )}
                            {req.status === 'APPROVED' && (
                              <>
                                <CheckCircle2 size={11} />
                                <span>Đã duyệt</span>
                              </>
                            )}
                            {req.status === 'REJECTED' && (
                              <>
                                <XCircle size={11} />
                                <span>Từ chối</span>
                              </>
                            )}
                            {req.status === 'CANCELLED' && (
                              <>
                                <Ban size={11} />
                                <span>Đã huỷ</span>
                              </>
                            )}
                            {req.status === 'EXPIRED' && (
                              <>
                                <AlertTriangle size={11} />
                                <span>Hết hạn</span>
                              </>
                            )}
                          </span>
                        </td>
                        <td style={{ fontSize: '12px', color: 'var(--theme-text-muted)' }}>
                          {formatDateTime(req.createdAt)}
                        </td>
                        <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                          <div style={{ display: 'inline-flex', alignItems: 'center', gap: '8px' }}>
                            {req.status === 'PENDING' && (
                              <button
                                type="button"
                                className="arp-btn arp-btn--danger-ghost arp-btn--sm"
                                onClick={() => {
                                  setCancelItem(req);
                                  setCancelError(null);
                                }}
                                title="Huỷ yêu cầu truy cập này"
                              >
                                Huỷ
                              </button>
                            )}
                            {req.status === 'REJECTED' && (
                              <button
                                type="button"
                                className="arp-link-btn"
                                onClick={() => toggleRejectReason(req.id)}
                              >
                                {expandedRejectIds.has(req.id) ? 'Ẩn lý do' : 'Xem lý do'}
                              </button>
                            )}
                            <button
                              type="button"
                              className="arp-btn arp-btn--secondary arp-btn--sm"
                              onClick={() => setSelectedDetail(req)}
                            >
                              Chi tiết
                            </button>
                          </div>
                        </td>
                      </tr>

                      {/* 3c. Hàng mở rộng lý do từ chối */}
                      {req.status === 'REJECTED' && expandedRejectIds.has(req.id) && (
                        <tr className="arp-reject-expand-row">
                          <td colSpan={6}>
                            <div className="arp-reject-expand-box">
                              <div className="arp-reject-expand-label">GHI CHÚ TỪ BAN QUẢN LÝ</div>
                              <div className="arp-reject-expand-content">
                                {req.rejectionReason || 'Không có lý do cụ thể được cung cấp.'}
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* 3e. Phân trang */}
        {historyTotalPages > 1 && (
          <div className="arp-pagination">
            <div className="arp-pagination__info">
              Hiển thị {historyTotalElements === 0 ? 0 : historyPage * 10 + 1}–
              {Math.min((historyPage + 1) * 10, historyTotalElements)} trên tổng số {historyTotalElements} yêu cầu
            </div>
            <div className="arp-pagination__controls">
              <button
                type="button"
                className="arp-page-btn"
                onClick={() => loadMyRequests(historyPage - 1, historyStatusFilter)}
                disabled={historyPage === 0 || loadingHistory}
                title="Trang trước"
              >
                <ChevronLeft size={14} />
              </button>

              {Array.from({ length: historyTotalPages }, (_, i) => i).map((p) => {
                if (
                  historyTotalPages <= 7 ||
                  p === 0 ||
                  p === historyTotalPages - 1 ||
                  Math.abs(p - historyPage) <= 1
                ) {
                  return (
                    <button
                      key={p}
                      type="button"
                      className={`arp-page-btn ${p === historyPage ? 'arp-page-btn--active' : ''}`}
                      onClick={() => loadMyRequests(p, historyStatusFilter)}
                      disabled={loadingHistory}
                    >
                      {p + 1}
                    </button>
                  );
                } else if (p === 1 || p === historyTotalPages - 2) {
                  return (
                    <span key={p} className="arp-page-ellipsis">
                      ...
                    </span>
                  );
                }
                return null;
              })}

              <button
                type="button"
                className="arp-page-btn"
                onClick={() => loadMyRequests(historyPage + 1, historyStatusFilter)}
                disabled={historyPage >= historyTotalPages - 1 || loadingHistory}
                title="Trang sau"
              >
                <ChevronRight size={14} />
              </button>
            </div>
          </div>
        )}
      </div>

      {/* 4. MODAL CHI TIẾT */}
      {selectedDetail && (
        <div className="arp-modal-overlay" onClick={() => setSelectedDetail(null)}>
          <div className="arp-modal" onClick={(e) => e.stopPropagation()}>
            <div className="arp-modal__header">
              <div className="arp-modal__header-title">
                <div className="arp-modal__icon-box">
                  <KeyRound size={16} />
                </div>
                <div>
                  <h2 className="arp-modal__title">Chi tiết Yêu cầu Truy cập</h2>
                  <div className="arp-modal__subtitle">Mã yêu cầu: #{selectedDetail.id?.substring(0, 8)}</div>
                </div>
              </div>
              <button
                type="button"
                className="arp-modal__close"
                onClick={() => setSelectedDetail(null)}
              >
                <X size={16} />
              </button>
            </div>

            <div className="arp-modal__body">
              <div className="arp-detail-grid">
                <div className="arp-detail-item">
                  <span className="arp-detail-label">Khu vực</span>
                  <span className="arp-detail-val">
                    {selectedDetail.areaName} ({selectedDetail.areaCode})
                  </span>
                  <span style={{ fontSize: '11px', color: 'var(--theme-text-muted)' }}>
                    Cấp độ: {selectedDetail.areaLevel} | {selectedDetail.building} - Tầng {selectedDetail.floor}
                  </span>
                </div>

                <div className="arp-detail-item">
                  <span className="arp-detail-label">Trạng thái</span>
                  <div>
                    <span className={`arp-status-badge arp-status-badge--${selectedDetail.status.toLowerCase()}`}>
                      {selectedDetail.status === 'PENDING' && (
                        <>
                          <Clock size={11} />
                          <span>Chờ phê duyệt</span>
                        </>
                      )}
                      {selectedDetail.status === 'APPROVED' && (
                        <>
                          <CheckCircle2 size={11} />
                          <span>Đã phê duyệt</span>
                        </>
                      )}
                      {selectedDetail.status === 'REJECTED' && (
                        <>
                          <XCircle size={11} />
                          <span>Bị từ chối</span>
                        </>
                      )}
                      {selectedDetail.status === 'CANCELLED' && (
                        <>
                          <Ban size={11} />
                          <span>Đã huỷ</span>
                        </>
                      )}
                      {selectedDetail.status === 'EXPIRED' && (
                        <>
                          <AlertTriangle size={11} />
                          <span>Hết hạn</span>
                        </>
                      )}
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
                  <span className="arp-detail-val">
                    {selectedDetail.requestType === 'GROUP' ? 'Tập thể / Nhóm' : 'Cá nhân'}
                  </span>
                </div>

                <div className="arp-detail-item">
                  <span className="arp-detail-label">Ngày gửi yêu cầu</span>
                  <span className="arp-detail-val">{formatDateTime(selectedDetail.createdAt)}</span>
                </div>
              </div>

              {/* Mục đích */}
              <div className="arp-detail-item">
                <span className="arp-detail-label">Mục đích sử dụng</span>
                <div className="arp-detail-box">{selectedDetail.purpose}</div>
              </div>

              {/* Thành viên (nếu nhóm) */}
              {selectedDetail.requestType === 'GROUP' &&
                selectedDetail.members &&
                selectedDetail.members.length > 0 && (
                  <div className="arp-detail-item">
                    <span className="arp-detail-label">
                      Danh sách thành viên nhóm ({selectedDetail.members.length})
                    </span>
                    <div className="arp-member-list">
                      {selectedDetail.members.map((m) => (
                        <span key={m.userId || m.userCode} className="arp-member-chip">
                          <strong>{m.userCode}</strong> · {m.fullName}
                        </span>
                      ))}
                    </div>
                  </div>
                )}

              {/* Lý do từ chối (nếu có) */}
              {selectedDetail.status === 'REJECTED' && selectedDetail.rejectionReason && (
                <div className="arp-detail-item">
                  <span className="arp-detail-label" style={{ color: 'var(--theme-danger)' }}>
                    Lý do từ chối
                  </span>
                  <div
                    className="arp-detail-box"
                    style={{
                      borderColor: 'var(--theme-danger-border)',
                      background: 'var(--theme-danger-bg)',
                      color: 'var(--theme-danger-text)'
                    }}
                  >
                    {selectedDetail.rejectionReason}
                  </div>
                </div>
              )}

              {/* Thông tin duyệt */}
              {selectedDetail.reviewedAt && (
                <div className="arp-detail-item">
                  <span className="arp-detail-label">Thông tin duyệt</span>
                  <div style={{ fontSize: '12px', color: 'var(--theme-text-secondary)' }}>
                    Duyệt bởi: <strong>{selectedDetail.reviewerName || 'Quản lý cơ sở'}</strong> vào lúc{' '}
                    {formatDateTime(selectedDetail.reviewedAt)}
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

      {/* MODAL XÁC NHẬN HUỶ YÊU CẦU */}
      <Modal
        isOpen={Boolean(cancelItem)}
        onClose={() => !cancelling && setCancelItem(null)}
        title="Xác nhận huỷ yêu cầu truy cập"
        subtitle="Thao tác này sẽ huỷ bỏ yêu cầu của bạn và không thể hoàn tác."
        icon={AlertTriangle}
        iconVariant="danger"
        size="md"
        footer={
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', width: '100%' }}>
            <Button
              variant="secondary"
              onClick={() => setCancelItem(null)}
              disabled={cancelling}
            >
              Đóng
            </Button>
            <Button
              variant="danger"
              onClick={handleConfirmCancel}
              loading={cancelling}
              disabled={cancelling}
            >
              Xác nhận huỷ
            </Button>
          </div>
        }
      >
        {cancelError && (
          <div className="arp-banner arp-banner--error" style={{ marginBottom: '14px' }}>
            <AlertCircle size={16} />
            <span>{cancelError}</span>
          </div>
        )}
        {cancelItem && (
          <div style={{ fontSize: '13px', lineHeight: '1.6', color: 'var(--theme-text-secondary)' }}>
            <p style={{ margin: '0 0 10px 0' }}>
              Bạn có chắc chắn muốn huỷ yêu cầu truy cập vào khu vực sau không?
            </p>
            <div style={{ background: 'var(--theme-bg-surface-elevated)', padding: '12px', borderRadius: '6px', border: '1px solid var(--theme-border)' }}>
              <div><strong>Khu vực:</strong> {cancelItem.areaName} ({cancelItem.areaCode})</div>
              <div><strong>Khung giờ:</strong> {formatTableTime(cancelItem.startTime, cancelItem.endTime)}</div>
              <div><strong>Hình thức:</strong> {cancelItem.requestType === 'GROUP' ? `Theo nhóm (${cancelItem.members?.length || 0} thành viên)` : 'Cá nhân'}</div>
              <div><strong>Mục đích:</strong> {cancelItem.purpose}</div>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}

