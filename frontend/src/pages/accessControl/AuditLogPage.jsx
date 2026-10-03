import React, { useState, useEffect, useCallback } from 'react';
import {
  Search,
  Loader2,
  History,
  RotateCcw,
  Filter,
  Link,
  X,
} from 'lucide-react';
import { toast } from 'sonner';
import { useAuth } from '../../context/AuthContext';
import { getAuditLogs } from '../../services/accessControlService';
import { getAreas } from '../../services/areaService';
import { ROLES } from '../../constants/roles';
import { formatDisplayDateTime } from '../../utils/areaHelpers';
import Button from '../../components/ui/Button';
import PageHeader from '../../components/ui/PageHeader';
import UserSearchCombobox from '../../components/user/UserSearchCombobox';
import './UserAccessLevelPage.css';

const SYSTEM_ACTOR_LABELS = {
  EXPIRE_OVERDUE_REQUESTS_JOB: 'Tự động hết hạn đơn quá giờ',
  EVENT_MODE_EXPIRY: 'Tự động kết thúc sự kiện hết hạn',
  EVENT_SCHEDULE_ACTIVATION: 'Tự động kích hoạt lịch sự kiện',
};

const renderSystemActor = (source) => {
  if (!source) return 'Hệ thống';
  return SYSTEM_ACTOR_LABELS[source] ? `Hệ thống – ${SYSTEM_ACTOR_LABELS[source]}` : `Hệ thống – ${source}`;
};

const MODULE_OPTIONS_ADMIN = [
  { value: '', label: 'Tất cả phân hệ' },
  { value: 'AREA', label: 'Khu vực' },
  { value: 'ACCESS_CONTROL', label: 'Phân quyền' },
  { value: 'ACCESS_REQUEST', label: 'Yêu cầu truy cập' },
  { value: 'SYSTEM', label: 'Hệ thống' },
];

const MODULE_OPTIONS_FM = [
  { value: '', label: 'Tất cả phân hệ' },
  { value: 'AREA', label: 'Khu vực' },
  { value: 'ACCESS_CONTROL', label: 'Phân quyền' },
  { value: 'ACCESS_REQUEST', label: 'Yêu cầu truy cập' },
];

const TARGET_TYPE_OPTIONS = [
  { value: '', label: 'Tất cả loại đối tượng' },
  { value: 'USER_ACCESS_LEVEL', label: 'Cấp truy cập người dùng' },
  { value: 'AREA_ACCESS_RULES', label: 'Quy tắc truy cập khu vực' },
  { value: 'AREA_ASSIGNMENT', label: 'Phân công nhân sự khu vực' },
  { value: 'LEVEL_PRESET', label: 'Mặc định theo loại khu vực' },
  { value: 'AREA_EVENT_MODE', label: 'Chế độ sự kiện' },
  { value: 'AREA_EVENT_SCHEDULE', label: 'Lịch sự kiện' },
  { value: 'REASON_CATALOG', label: 'Danh mục lý do' },
  { value: 'AREA', label: 'Khu vực' },
  { value: 'AREA_GEOMETRY', label: 'Tọa độ khu vực' },
  { value: 'AREA_CAMERAS', label: 'Camera khu vực' },
  { value: 'ACCESS_REQUEST', label: 'Yêu cầu truy cập' },
  { value: 'SYSTEM_CONFIG', label: 'Cấu hình hệ thống' },
  { value: 'SYSTEM', label: 'Hệ thống' },
];

export default function AuditLogPage() {
  const { user: currentUser } = useAuth();
  const isAdmin = currentUser?.role === ROLES.ADMIN;

  const [logs, setLogs] = useState([]);
  const [loadingLogs, setLoadingLogs] = useState(false);
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [pageSize] = useState(20);

  // Filters
  const [filterModule, setFilterModule] = useState('');
  const [filterCorrelationId, setFilterCorrelationId] = useState('');
  const [filterTargetType, setFilterTargetType] = useState('');
  const [filterAreaId, setFilterAreaId] = useState('');
  const [filterSubjectUser, setFilterSubjectUser] = useState(null);
  const [filterChangedByUser, setFilterChangedByUser] = useState(null);
  const [filterFrom, setFilterFrom] = useState('');
  const [filterTo, setFilterTo] = useState('');

  const [areasList, setAreasList] = useState([]);

  useEffect(() => {
    const fetchAreas = async () => {
      try {
        const res = await getAreas({ size: 100 });
        setAreasList(res?.content || []);
      } catch (err) {
        console.error('Lỗi tải danh sách khu vực:', err);
      }
    };
    fetchAreas();
  }, []);

  const loadAuditLogs = useCallback(
    async (page = 0, overrideCorrelationId = undefined) => {
      setLoadingLogs(true);
      try {
        const corrId = overrideCorrelationId !== undefined ? overrideCorrelationId : filterCorrelationId;
        const params = {
          page,
          size: pageSize,
        };
        if (filterModule) params.module = filterModule;
        if (corrId) params.correlationId = corrId;
        if (filterTargetType) params.targetType = filterTargetType;
        if (filterAreaId) params.areaId = filterAreaId;
        if (filterSubjectUser?.id) params.subjectUserId = filterSubjectUser.id;
        if (filterChangedByUser?.id) params.changedBy = filterChangedByUser.id;
        if (filterFrom) params.from = `${filterFrom}T00:00:00Z`;
        if (filterTo) params.to = `${filterTo}T23:59:59Z`;

        const res = await getAuditLogs(params);
        setLogs(res?.content || []);
        setTotalPages(res?.totalPages || 0);
        setTotalElements(res?.totalElements || 0);
        setCurrentPage(page);
      } catch (err) {
        console.error('Lỗi tải nhật ký thay đổi:', err);
        const httpStatus = err?.status ?? err?.response?.status;
        if (httpStatus === 403) {
          toast.error('Bạn không có quyền xem nhật ký của phân hệ này');
        } else {
          toast.error(err?.message || 'Không thể tải nhật ký phân quyền');
        }
        setLogs([]);
      } finally {
        setLoadingLogs(false);
      }
    },
    [
      filterModule,
      filterCorrelationId,
      filterTargetType,
      filterAreaId,
      filterSubjectUser,
      filterChangedByUser,
      filterFrom,
      filterTo,
      pageSize,
    ]
  );

  useEffect(() => {
    loadAuditLogs(0);
  }, [loadAuditLogs]);

  const handleFilterByCorrelation = (correlationId) => {
    if (!correlationId) return;
    setFilterCorrelationId(correlationId);
    loadAuditLogs(0, correlationId);
  };

  const handleClearCorrelationFilter = () => {
    setFilterCorrelationId('');
    loadAuditLogs(0, '');
  };

  const handleResetFilters = () => {
    setFilterModule('');
    setFilterCorrelationId('');
    setFilterTargetType('');
    setFilterAreaId('');
    setFilterFrom('');
    setFilterTo('');
    setFilterSubjectUser(null);
    setFilterChangedByUser(null);
  };

  const renderAuditTargetType = (targetType, action) => {
    let label = targetType;
    let badgeClass = 'audit-type--default';

    if (targetType === 'USER_ACCESS_LEVEL') {
      label = 'Cấp người dùng';
      badgeClass = 'audit-type--user';
    } else if (targetType === 'AREA_ACCESS_RULES') {
      label = 'Quy tắc khu vực';
      badgeClass = 'audit-type--area';
    } else if (targetType === 'AREA_ASSIGNMENT') {
      label = 'Nhân sự chỉ định';
      badgeClass = 'audit-type--personnel';
    } else if (targetType === 'LEVEL_PRESET') {
      label = 'Mặc định loại';
      badgeClass = 'audit-type--preset';
    } else if (targetType === 'AREA_EVENT_MODE') {
      label = 'Chế độ sự kiện';
      badgeClass = 'audit-type--event';
    } else if (targetType === 'AREA_EVENT_SCHEDULE') {
      label = 'Lịch sự kiện';
      badgeClass = 'audit-type--event';
    } else if (targetType === 'REASON_CATALOG') {
      label = 'Danh mục lý do';
      badgeClass = 'audit-type--catalog';
    } else if (targetType === 'AREA') {
      label = 'Khu vực';
      badgeClass = 'audit-type--area';
    } else if (targetType === 'AREA_GEOMETRY') {
      label = 'Tọa độ khu vực';
      badgeClass = 'audit-type--area';
    } else if (targetType === 'AREA_CAMERAS') {
      label = 'Camera khu vực';
      badgeClass = 'audit-type--area';
    } else if (targetType === 'ACCESS_REQUEST') {
      label = 'Yêu cầu truy cập';
      badgeClass = 'audit-type--request';
    } else if (targetType === 'SYSTEM_CONFIG') {
      label = 'Cấu hình hệ thống';
      badgeClass = 'audit-type--system';
    } else if (targetType === 'SYSTEM') {
      label = 'Hệ thống';
      badgeClass = 'audit-type--system';
    }

    let actionLabel = action;
    if (action === 'UPDATE') actionLabel = 'Cập nhật';
    else if (action === 'ASSIGN') actionLabel = 'Gán mới';
    else if (action === 'UPDATE_VALIDITY') actionLabel = 'Gia hạn';
    else if (action === 'REVOKE') actionLabel = 'Thu hồi';
    else if (action === 'ENABLE_EVENT_MODE') actionLabel = 'Bật chế độ sự kiện';
    else if (action === 'DISABLE_EVENT_MODE') actionLabel = 'Tắt chế độ sự kiện';
    else if (action === 'EXTEND_EVENT_MODE') actionLabel = 'Điều chỉnh giờ kết thúc';
    else if (action === 'CREATE') actionLabel = 'Tạo mới';
    else if (action === 'DEACTIVATE') actionLabel = 'Ngừng dùng';
    else if (action === 'REACTIVATE') actionLabel = 'Dùng lại';
    else if (action === 'UPDATE_RULES') actionLabel = 'Cập nhật quy tắc';
    else if (action === 'UPDATE_PRESET') actionLabel = 'Cập nhật mặc định';
    else if (action === 'UPDATE_GEOMETRY') actionLabel = 'Cập nhật tọa độ';
    else if (action === 'DELETE_GEOMETRY') actionLabel = 'Xóa tọa độ';
    else if (action === 'UPDATE_CAMERAS') actionLabel = 'Cập nhật camera';
    else if (action === 'SUBMIT') actionLabel = 'Gửi yêu cầu';
    else if (action === 'APPROVE') actionLabel = 'Phê duyệt';
    else if (action === 'REJECT') actionLabel = 'Từ chối';
    else if (action === 'CANCEL') actionLabel = 'Hủy bỏ';
    else if (action === 'FINISH') actionLabel = 'Kết thúc';
    else if (action === 'EXPIRE') actionLabel = 'Hết hạn';
    else if (action === 'AUTO_EXPIRE') actionLabel = 'Tự động hết hạn';
    else if (action === 'EXPIRE_EVENT_MODE') actionLabel = 'Sự kiện hết hạn';

    if (targetType === 'AREA_EVENT_SCHEDULE') {
      if (action === 'CREATE') actionLabel = 'Đặt lịch';
      else if (action === 'UPDATE') actionLabel = 'Sửa lịch';
      else if (action === 'CANCEL') actionLabel = 'Huỷ lịch';
      else if (action === 'FAIL') actionLabel = 'Lịch thất bại';
    }

    return (
      <div className="audit-target-col">
        <span className={`audit-badge ${badgeClass}`}>{label}</span>
        <span className="audit-action-label">{actionLabel}</span>
      </div>
    );
  };

  const getAuditReasonText = (log) => {
    const { targetType, action, oldValue, newValue } = log;
    let label = null;
    if (targetType === 'AREA_EVENT_SCHEDULE') {
      const snap = newValue || oldValue || {};
      label = action === 'CANCEL' ? snap.cancelReasonLabel || snap.reasonLabel : snap.reasonLabel;
    } else if (['ENABLE_EVENT_MODE', 'DISABLE_EVENT_MODE', 'EXTEND_EVENT_MODE'].includes(action)) {
      label = newValue?.reasonLabel || oldValue?.reasonLabel;
    }
    if (label && log.reason && log.reason !== label) return `${label} – ${log.reason}`;
    return log.reason || label || null;
  };

  const renderAuditChange = (log) => {
    const { targetType, action, oldValue, newValue } = log;

    if (action === 'EXPIRE_EVENT_MODE') {
      const plannedEnd = newValue?.plannedEnd || oldValue?.plannedEnd;
      return (
        <div className="audit-detail-rules">
          <div className="audit-detail-row">
            <span className="audit-row-label">Kết thúc dự kiến:</span>
            <strong className="audit-val--new">{plannedEnd ? formatDisplayDateTime(plannedEnd) : '—'}</strong>
          </div>
        </div>
      );
    }

    if (targetType === 'AREA_EVENT_SCHEDULE') {
      const snap = newValue || oldValue || {};
      const formatRange = (v) =>
        v?.startAt || v?.endAt
          ? `${v?.startAt ? formatDisplayDateTime(v.startAt) : '—'} → ${v?.endAt ? formatDisplayDateTime(v.endAt) : '—'}`
          : '—';
      const oldRange = formatRange(oldValue);
      const newRange = formatRange(newValue);
      const statusLabels = {
        SCHEDULED: 'Đã lên lịch',
        STARTED: 'Đã bắt đầu',
        COMPLETED: 'Đã kết thúc',
        ENDED_EARLY: 'Kết thúc sớm',
        CANCELLED: 'Đã huỷ',
        FAILED: 'Thất bại',
      };
      const oldStatus = oldValue?.status;
      const newStatus = newValue?.status;

      return (
        <div className="audit-detail-rules">
          <div className="audit-detail-row">
            <span className="audit-row-label">Thời gian:</span>
            {oldValue && newValue && oldRange !== newRange ? (
              <>
                <span>{oldRange}</span>
                <span className="audit-arrow">→</span>
                <strong className="audit-val--new">{newRange}</strong>
              </>
            ) : (
              <strong className="audit-val--new">{newValue ? newRange : oldRange}</strong>
            )}
          </div>
          {(oldStatus || newStatus) && (
            <div className="audit-detail-row">
              <span className="audit-row-label">Trạng thái:</span>
              {oldStatus && newStatus && oldStatus !== newStatus ? (
                <>
                  <span>{statusLabels[oldStatus] || oldStatus}</span>
                  <span className="audit-arrow">→</span>
                  <strong className="audit-val--new">{statusLabels[newStatus] || newStatus}</strong>
                </>
              ) : (
                <strong className="audit-val--new">{statusLabels[newStatus || oldStatus] || newStatus || oldStatus}</strong>
              )}
            </div>
          )}
          {action === 'FAIL' && snap.failReason && (
            <div className="audit-detail-row">
              <span className="audit-row-label">Lý do lỗi:</span>
              <span className="audit-val--fail">{snap.failReason}</span>
            </div>
          )}
        </div>
      );
    }

    if (targetType === 'USER_ACCESS_LEVEL') {
      const oldLvl = oldValue?.accessLevel;
      const newLvl = newValue?.accessLevel;
      return (
        <div className="audit-detail-rules">
          <div className="audit-detail-row">
            <span className="audit-row-label">Cấp độ:</span>
            {oldLvl !== undefined && newLvl !== undefined ? (
              <>
                <span>Cấp {oldLvl}</span>
                <span className="audit-arrow">→</span>
                <strong className="audit-val--new">Cấp {newLvl}</strong>
              </>
            ) : (
              <strong className="audit-val--new">Cấp {newLvl ?? oldLvl}</strong>
            )}
          </div>
        </div>
      );
    }

    if (targetType === 'AREA_ACCESS_RULES' || targetType === 'LEVEL_PRESET') {
      const oldLvl = oldValue?.areaAccessLevel ?? oldValue?.accessLevel;
      const newLvl = newValue?.areaAccessLevel ?? newValue?.accessLevel;
      const oldExp = oldValue?.explicitAuthorizationRequired;
      const newExp = newValue?.explicitAuthorizationRequired;

      return (
        <div className="audit-detail-rules">
          <div className="audit-detail-row">
            <span className="audit-row-label">Cấp truy cập:</span>
            {oldLvl !== undefined && newLvl !== undefined && oldLvl !== newLvl ? (
              <>
                <span>Cấp {oldLvl}</span>
                <span className="audit-arrow">→</span>
                <strong className="audit-val--new">Cấp {newLvl}</strong>
              </>
            ) : (
              <strong className="audit-val--new">Cấp {newLvl ?? oldLvl ?? '—'}</strong>
            )}
          </div>
          <div className="audit-detail-row">
            <span className="audit-row-label">Yêu cầu chỉ định:</span>
            {oldExp !== undefined && newExp !== undefined && oldExp !== newExp ? (
              <>
                <span>{oldExp ? 'Bật' : 'Tắt'}</span>
                <span className="audit-arrow">→</span>
                <strong className="audit-val--new">{newExp ? 'Bật' : 'Tắt'}</strong>
              </>
            ) : (
              <strong className="audit-val--new">{(newExp ?? oldExp) ? 'Bật' : 'Tắt'}</strong>
            )}
          </div>
        </div>
      );
    }

    // Default JSON fallback summary
    const snap = newValue || oldValue;
    if (!snap) return <span className="audit-cell--empty">—</span>;
    return (
      <div className="audit-detail-rules">
        <span className="audit-val--json-summary">
          {typeof snap === 'object' ? Object.keys(snap).slice(0, 3).join(', ') : String(snap)}
        </span>
      </div>
    );
  };

  return (
    <div className="user-access-level-page">
      <PageHeader
        title="Nhật ký Kiểm toán & Phân quyền"
        subtitle="Theo dõi toàn bộ lịch sử thay đổi phân quyền, quy tắc khu vực và các thao tác trong hệ thống."
        actions={
          <Button
            variant="outline"
            size="sm"
            onClick={() => loadAuditLogs(currentPage)}
            disabled={loadingLogs}
            leftIcon={<RotateCcw size={16} className={loadingLogs ? 'animate-spin' : ''} />}
          >
            Làm mới
          </Button>
        }
      />

      <div className="tab-pane">
        {filterCorrelationId && (
          <div className="audit-correlation-banner">
            <div className="audit-correlation-banner__content">
              <Link size={15} />
              <span>Đang lọc theo mã thao tác (Correlation ID):</span>
              <span className="audit-correlation-banner__id">{filterCorrelationId}</span>
            </div>
            <button
              type="button"
              className="audit-correlation-clear-btn"
              onClick={handleClearCorrelationFilter}
            >
              <X size={13} />
              <span>Xóa lọc</span>
            </button>
          </div>
        )}

        {/* Audit Filters Toolbar */}
        <div className="audit-filters-card">
          <div className="audit-filters-header">
            <Filter size={16} />
            <span>Bộ lọc nhật ký</span>
          </div>
          <div className="audit-filters-grid">
            <div className="audit-filter-item">
              <label className="audit-filter-label">Phân hệ</label>
              <select
                className="audit-filter-select"
                value={filterModule}
                onChange={(e) => setFilterModule(e.target.value)}
              >
                {(isAdmin ? MODULE_OPTIONS_ADMIN : MODULE_OPTIONS_FM).map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="audit-filter-item">
              <label className="audit-filter-label">Loại đối tượng</label>
              <select
                className="audit-filter-select"
                value={filterTargetType}
                onChange={(e) => setFilterTargetType(e.target.value)}
              >
                {TARGET_TYPE_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="audit-filter-item">
              <label className="audit-filter-label">Khu vực</label>
              <select
                className="audit-filter-select"
                value={filterAreaId}
                onChange={(e) => setFilterAreaId(e.target.value)}
              >
                <option value="">Tất cả khu vực</option>
                {areasList.map((a) => {
                  const floorPart = a.floor ? (String(a.floor).startsWith('Tầng') ? a.floor : `Tầng ${a.floor}`) : null;
                  const loc = [a.building, floorPart].filter(Boolean).join(' · ');
                  return (
                    <option key={a.id} value={a.id}>
                      {loc ? `${a.name} (${loc})` : a.name}
                    </option>
                  );
                })}
              </select>
            </div>

            <div className="audit-filter-item">
              <label className="audit-filter-label">Từ ngày</label>
              <input
                type="date"
                className="audit-filter-input"
                value={filterFrom}
                onChange={(e) => setFilterFrom(e.target.value)}
              />
            </div>

            <div className="audit-filter-item">
              <label className="audit-filter-label">Đến ngày</label>
              <input
                type="date"
                className="audit-filter-input"
                value={filterTo}
                onChange={(e) => setFilterTo(e.target.value)}
              />
            </div>

            <div className="audit-filter-item audit-filter-item--wide">
              <label className="audit-filter-label">Người bị tác động</label>
              <UserSearchCombobox
                selectedUser={filterSubjectUser}
                onSelect={(u) => setFilterSubjectUser(u)}
                onClear={() => setFilterSubjectUser(null)}
                placeholder="Tìm theo tên/mã người bị tác động..."
              />
            </div>

            <div className="audit-filter-item audit-filter-item--wide">
              <label className="audit-filter-label">Người thực hiện</label>
              <UserSearchCombobox
                selectedUser={filterChangedByUser}
                onSelect={(u) => setFilterChangedByUser(u)}
                onClear={() => setFilterChangedByUser(null)}
                placeholder="Tìm theo tên/mã người thực hiện..."
              />
            </div>
          </div>

          <div className="audit-filters-actions">
            <Button
              variant="secondary"
              size="sm"
              leftIcon={<RotateCcw size={15} />}
              onClick={handleResetFilters}
            >
              Đặt lại
            </Button>
            <Button
              variant="primary"
              size="sm"
              leftIcon={<Search size={15} />}
              onClick={() => loadAuditLogs(0)}
            >
              Áp dụng lọc
            </Button>
          </div>
        </div>

        {/* Audit Logs Table */}
        <div className="section-card">
          {loadingLogs ? (
            <div className="loading-state">
              <Loader2 size={28} className="animate-spin" />
              <span>Đang tải nhật ký thay đổi...</span>
            </div>
          ) : logs.length === 0 ? (
            <div className="empty-state">
              <History size={32} />
              <p className="empty-state__title">Không tìm thấy bản ghi nhật ký phù hợp</p>
              <span className="empty-state__desc">
                Thử thay đổi bộ lọc tìm kiếm để xem kết quả khác.
              </span>
            </div>
          ) : (
            <>
              <div className="table-wrapper">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th className="ui-col-time">Thời gian</th>
                      <th>Thao tác & Đối tượng</th>
                      <th>Khu vực</th>
                      <th>Người bị tác động</th>
                      <th>Người thực hiện</th>
                      <th>Nội dung thay đổi</th>
                      <th>Lý do</th>
                      <th>Liên kết</th>
                    </tr>
                  </thead>
                  <tbody>
                    {logs.map((log) => (
                      <tr key={log.id}>
                        <td className="audit-cell--time">
                          {formatDisplayDateTime(log.changedAt)}
                        </td>
                        <td>
                          {renderAuditTargetType(log.targetType, log.action)}
                        </td>
                        <td>
                          {log.areaName ? (
                            <div className="audit-area-cell">
                              <span className="audit-area-name">{log.areaName}</span>
                            </div>
                          ) : (
                            <span className="audit-cell--empty">—</span>
                          )}
                        </td>
                        <td>
                          {log.subjectUserName ? (
                            <div className="audit-user-cell">
                              <span className="audit-user-name">{log.subjectUserName}</span>
                              <span className="audit-user-code">{log.subjectUserCode}</span>
                            </div>
                          ) : (
                            <span className="audit-cell--empty">—</span>
                          )}
                        </td>
                        <td>
                          {log.actorType === 'SYSTEM' || !log.changedByName ? (
                            <div className="audit-user-cell">
                              <span className="audit-user-name audit-system-actor">
                                {renderSystemActor(log.actorSource)}
                              </span>
                            </div>
                          ) : (
                            <div className="audit-user-cell">
                              <span className="audit-user-name">{log.changedByName}</span>
                              {log.changedByUserCode && (
                                <span className="audit-user-code">{log.changedByUserCode}</span>
                              )}
                            </div>
                          )}
                        </td>
                        <td>
                          {renderAuditChange(log)}
                        </td>
                        <td className="audit-cell--reason">
                          {getAuditReasonText(log) ? (
                            <span className="audit-reason-text" title={getAuditReasonText(log)}>
                              {getAuditReasonText(log)}
                            </span>
                          ) : (
                            <span className="audit-cell--empty">—</span>
                          )}
                        </td>
                        <td>
                          {log.correlationId ? (
                            <button
                              type="button"
                              className="audit-btn-link"
                              title={`Xem các bản ghi cùng thao tác (${log.correlationId})`}
                              onClick={() => handleFilterByCorrelation(log.correlationId)}
                            >
                              <Link size={13} />
                              <span>Cùng thao tác</span>
                            </button>
                          ) : (
                            <span className="audit-cell--empty">—</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {totalPages > 1 && (
                <div className="pagination-bar">
                  <span className="pagination-info">
                    Trang {currentPage + 1} / {totalPages} ({totalElements} bản ghi)
                  </span>
                  <div className="pagination-actions">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={currentPage === 0 || loadingLogs}
                      onClick={() => loadAuditLogs(currentPage - 1)}
                    >
                      Trang trước
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={currentPage >= totalPages - 1 || loadingLogs}
                      onClick={() => loadAuditLogs(currentPage + 1)}
                    >
                      Trang sau
                    </Button>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
