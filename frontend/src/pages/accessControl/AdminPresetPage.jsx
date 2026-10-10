import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Sliders,
  Edit3,
  Loader2,
  Info,
  ShieldCheck,
  RotateCcw,
} from 'lucide-react';
import { toast } from 'sonner';
import { getLevelPresets, updateLevelPreset } from '../../services/accessControlService';
import { getLevelConfig, getAccessLevelConfig } from '../../utils/areaHelpers';
import Button from '../../components/ui/Button';
import Modal from '../../components/ui/Modal';
import PageHeader from '../../components/ui/PageHeader';
import { LoadingState, EmptyState, ErrorState } from '../../components/ui';
import ReasonTextarea from '../../components/ui/ReasonTextarea';
import '../../styles/UserAccessLevelPage.css';
import '../../styles/AdminPresetPage.css';

const ACCESS_LEVELS = [
  { level: 1, name: 'Cấp 1 — Mọi người dùng' },
  { level: 2, name: 'Cấp 2 — Nhân viên' },
  { level: 3, name: 'Cấp 3 — Cấp cao' },
];

export default function AdminPresetPage() {
  const [presets, setPresets] = useState([]);
  const [loadingPresets, setLoadingPresets] = useState(false);
  const [loadError, setLoadError] = useState(null);

  const [editPresetModal, setEditPresetModal] = useState({
    isOpen: false,
    preset: null,
    accessLevel: 1,
    explicitAuthorizationRequired: false,
    reason: '',
    reasonError: '',
    isSaving: false,
  });
  const reasonRef = useRef(null);

  const loadPresets = useCallback(async () => {
    setLoadingPresets(true);
    setLoadError(null);
    try {
      const data = await getLevelPresets();
      setPresets(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Lỗi tải danh sách cấu hình mặc định:', err);
      setLoadError(err?.message || 'Không thể tải cấu hình mặc định');
    } finally {
      setLoadingPresets(false);
    }
  }, []);

  useEffect(() => {
    loadPresets();
  }, [loadPresets]);

  const openEditPresetModal = (preset) => {
    const areaLevelKey = preset.areaLevel || preset.areaType;
    const isExplicitFixed =
      areaLevelKey === 'CONFIDENTIAL_CONTACT_REQUIRED' ||
      areaLevelKey === 'HIGHLY_CONFIDENTIAL';

    setEditPresetModal({
      isOpen: true,
      preset,
      accessLevel: preset.areaAccessLevel ?? preset.accessLevel ?? 1,
      explicitAuthorizationRequired: isExplicitFixed,
      reason: '',
      reasonError: '',
      isSaving: false,
    });
  };

  const handleSavePreset = async () => {
    const { preset, accessLevel, explicitAuthorizationRequired, reason } = editPresetModal;
    const trimmedReason = reason?.trim() || '';
    if (trimmedReason.length < 10 || trimmedReason.length > 500) {
      setEditPresetModal((prev) => ({
        ...prev,
        reasonError: `Lý do phải từ 10 đến 500 ký tự (hiện có ${trimmedReason.length}).`,
      }));
      reasonRef.current?.focus();
      return;
    }

    const areaLevelKey = preset.areaLevel || preset.areaType;
    const cfg = getLevelConfig(areaLevelKey);

    setEditPresetModal((prev) => ({ ...prev, isSaving: true }));
    try {
      await updateLevelPreset(areaLevelKey, {
        areaAccessLevel: accessLevel,
        explicitAuthorizationRequired,
        reason: trimmedReason,
        version: preset.version,
      });
      toast.success(`Đã cập nhật cấu hình mặc định cho loại ${cfg.name}`);
      setEditPresetModal({
        isOpen: false,
        preset: null,
        accessLevel: 1,
        explicitAuthorizationRequired: false,
        reason: '',
        reasonError: '',
        isSaving: false,
      });
      loadPresets();
    } catch (err) {
      console.error('Lỗi lưu cấu hình preset:', err);
      if (err?.status === 409 || err?.code === 'ERR_AC_003') {
        toast.error('Dữ liệu cấu hình đã bị thay đổi bởi người khác. Vui lòng thử lại.');
        loadPresets();
      } else {
        toast.error(err?.message || 'Không thể cập nhật cấu hình mặc định');
      }
      setEditPresetModal((prev) => ({ ...prev, isSaving: false }));
    }
  };

  return (
    <div className="admin-preset-page">
      <PageHeader
        title="Mặc định theo loại khu vực"
        description="Quản lý cấp độ truy cập mặc định cho từng loại khu vực trong toàn bộ khuôn viên."
        actions={
          <Button
            variant="outline"
            size="sm"
            onClick={loadPresets}
            disabled={loadingPresets}
            icon={<RotateCcw size={16} className={loadingPresets ? 'animate-spin' : ''} />}
          >
            Làm mới
          </Button>
        }
      />

      <div className="tab-pane">
        <div className="section-card">
          <div className="section-card__header">
            <div className="section-card__title">
              <Sliders size={20} />
              <span>Danh sách Cấu hình Mặc định</span>
            </div>
            <p className="section-card__desc">
              Khi tạo mới hoặc cập nhật loại của một khu vực, các thiết lập mặc định dưới đây sẽ được tự động áp dụng.
            </p>
          </div>

          {loadingPresets ? (
            <LoadingState text="Đang tải cấu hình mặc định..." />
          ) : loadError ? (
            <ErrorState message={loadError} onRetry={loadPresets} />
          ) : presets.length === 0 ? (
            <EmptyState title="Chưa có cấu hình mặc định" />
          ) : (
            <div className="table-wrapper">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Loại khu vực</th>
                    <th>Cấp truy cập mặc định</th>
                    <th>Yêu cầu chỉ định</th>
                    <th>Phiên bản</th>
                    <th className="text-right">Thao tác</th>
                  </tr>
                </thead>
                <tbody>
                  {presets.map((p) => {
                    const levelKey = p.areaLevel || p.areaType;
                    const cfg = getLevelConfig(levelKey);
                    const currentAccessLevel = p.areaAccessLevel ?? p.accessLevel ?? 1;
                    const accessCfg = getAccessLevelConfig(currentAccessLevel);
                    const isExplicit = Boolean(p.explicitAuthorizationRequired);

                    return (
                      <tr key={levelKey}>
                        <td>
                          <div className="area-type-col">
                            <span
                              className="area-type-badge"
                              style={{
                                backgroundColor: cfg.bgColor || 'var(--bg-card)',
                                color: cfg.color || 'inherit',
                                borderColor: cfg.borderColor || 'transparent',
                              }}
                            >
                              {cfg.name}
                            </span>
                            <span className="area-type-desc">{cfg.description}</span>
                          </div>
                        </td>
                        <td>
                          <span
                            className="access-level-badge"
                            style={{
                              backgroundColor: accessCfg.bgColor,
                              color: accessCfg.color,
                              borderColor: accessCfg.borderColor,
                            }}
                          >
                            {accessCfg.name} (Cấp {currentAccessLevel})
                          </span>
                        </td>
                        <td>
                          {isExplicit ? (
                            <span className="badge badge--danger" title="Bắt buộc có phê duyệt hoặc phân công riêng">
                              Bắt buộc chỉ định
                            </span>
                          ) : (
                            <span className="badge badge--success" title="Theo cấp độ truy cập thông thường">
                              Không bắt buộc
                            </span>
                          )}
                        </td>
                        <td>
                          <span className="version-tag">v{p.version ?? 0}</span>
                        </td>
                        <td className="text-right">
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => openEditPresetModal(p)}
                            icon={<Edit3 size={15} />}
                          >
                            Chỉnh sửa
                          </Button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Modal Chỉnh sửa Preset */}
      {editPresetModal.isOpen && (
        <Modal
          isOpen={editPresetModal.isOpen}
          onClose={() =>
            !editPresetModal.isSaving &&
            setEditPresetModal((prev) => ({ ...prev, isOpen: false }))
          }
          title="Chỉnh sửa Cấu hình Mặc định"
        >
          {(() => {
            const levelKey = editPresetModal.preset?.areaLevel || editPresetModal.preset?.areaType;
            const cfg = getLevelConfig(levelKey);
            const isExplicitFixed =
              levelKey === 'CONFIDENTIAL_CONTACT_REQUIRED' ||
              levelKey === 'HIGHLY_CONFIDENTIAL';

            return (
              <div className="preset-modal-content">
                <div className="modal-field">
                  <label className="modal-label">Loại khu vực</label>
                  <div
                    className="area-type-badge-preview"
                    style={{
                      backgroundColor: cfg.bgColor || 'var(--bg-card)',
                      color: cfg.color || 'inherit',
                      borderColor: cfg.borderColor || 'transparent',
                    }}
                  >
                    <strong>{cfg.name}</strong> — {cfg.description}
                  </div>
                </div>

                <div className="modal-field">
                  <label className="modal-label">Cấp độ truy cập mặc định</label>
                  <select
                    className="form-control"
                    value={editPresetModal.accessLevel}
                    onChange={(e) =>
                      setEditPresetModal((prev) => ({
                        ...prev,
                        accessLevel: Number(e.target.value),
                      }))
                    }
                    disabled={editPresetModal.isSaving}
                  >
                    {ACCESS_LEVELS.map((al) => (
                      <option key={al.level} value={al.level}>
                        {al.name}
                      </option>
                    ))}
                  </select>
                  <span className="field-hint">
                    Người dùng đạt cấp độ này trở lên mới có thể truy cập khu vực thuộc loại này.
                  </span>
                </div>

                <div className="modal-field">
                  <label className="modal-label">Yêu cầu chỉ định</label>
                  <div className="explicit-toggle-wrapper">
                    <label className="switch-disabled">
                      <input
                        type="checkbox"
                        checked={isExplicitFixed}
                        disabled
                        readOnly
                      />
                      <span className="switch-slider" />
                    </label>
                    <span className="explicit-status-text">
                      {isExplicitFixed ? 'Bắt buộc chỉ định (Bật)' : 'Không bắt buộc (Tắt)'}
                    </span>
                  </div>
                  <span className="field-hint text-muted">
                    <Info size={14} style={{ display: 'inline', marginRight: '4px', verticalAlign: 'middle' }} />
                    Cờ yêu cầu chỉ định được suy ra cố định từ loại khu vực và không thể thay đổi riêng lẻ.
                  </span>
                </div>

                <div className="modal-field">
                  <ReasonTextarea
                    ref={reasonRef}
                    label="Lý do thay đổi"
                    required
                    value={editPresetModal.reason}
                    onChange={(e) => {
                      const val = e.target.value;
                      setEditPresetModal((prev) => ({
                        ...prev,
                        reason: val,
                        reasonError:
                          prev.reasonError && val.trim().length >= 10 && val.trim().length <= 500
                            ? ''
                            : prev.reasonError,
                      }));
                    }}
                    minLength={10}
                    maxLength={500}
                    placeholder="Nhập lý do điều chỉnh cấu hình mặc định (từ 10 đến 500 ký tự)..."
                    rows={3}
                    error={editPresetModal.reasonError}
                    disabled={editPresetModal.isSaving}
                  />
                </div>

                <div className="modal-actions">
                  <Button
                    variant="outline"
                    onClick={() =>
                      setEditPresetModal((prev) => ({ ...prev, isOpen: false }))
                    }
                    disabled={editPresetModal.isSaving}
                  >
                    Hủy bỏ
                  </Button>
                  <Button
                    variant="primary"
                    onClick={handleSavePreset}
                    disabled={editPresetModal.isSaving}
                    icon={
                      editPresetModal.isSaving ? (
                        <Loader2 size={16} className="animate-spin" />
                      ) : (
                        <ShieldCheck size={16} />
                      )
                    }
                  >
                    {editPresetModal.isSaving ? 'Đang lưu...' : 'Lưu thay đổi'}
                  </Button>
                </div>
              </div>
            );
          })()}
        </Modal>
      )}
    </div>
  );
}
