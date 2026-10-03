import React, { useState, useEffect, useCallback } from 'react';
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
import './UserAccessLevelPage.css';

const ACCESS_LEVELS = [
  { level: 1, name: 'Cấp 1 — Mọi người dùng' },
  { level: 2, name: 'Cấp 2 — Nhân viên' },
  { level: 3, name: 'Cấp 3 — Cấp cao' },
];

export default function AdminPresetPage() {
  const [presets, setPresets] = useState([]);
  const [loadingPresets, setLoadingPresets] = useState(false);

  const [editPresetModal, setEditPresetModal] = useState({
    isOpen: false,
    preset: null,
    accessLevel: 1,
    explicitAuthorizationRequired: false,
    reason: '',
    isSaving: false,
  });

  const loadPresets = useCallback(async () => {
    setLoadingPresets(true);
    try {
      const data = await getLevelPresets();
      setPresets(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Lỗi tải danh sách cấu hình mặc định:', err);
      toast.error(err?.message || 'Không thể tải cấu hình mặc định');
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
      isSaving: false,
    });
  };

  const handleSavePreset = async () => {
    const { preset, accessLevel, explicitAuthorizationRequired, reason } = editPresetModal;
    const trimmedReason = reason?.trim();
    if (!trimmedReason) {
      toast.error('Vui lòng nhập lý do thay đổi cấu hình mặc định');
      return;
    }
    if (trimmedReason.length < 10) {
      toast.error('Lý do phải có từ 10 đến 500 ký tự');
      return;
    }
    if (trimmedReason.length > 500) {
      toast.error('Lý do không được vượt quá 500 ký tự');
      return;
    }

    const areaLevelKey = preset.areaLevel || preset.areaType;
    const cfg = getLevelConfig(areaLevelKey);

    setEditPresetModal((prev) => ({ ...prev, isSaving: true }));
    try {
      await updateLevelPreset(areaLevelKey, {
        areaAccessLevel: accessLevel,
        explicitAuthorizationRequired,
        reason: reason.trim(),
        version: preset.version,
      });
      toast.success(`Đã cập nhật cấu hình mặc định cho loại ${cfg.name}`);
      setEditPresetModal({
        isOpen: false,
        preset: null,
        accessLevel: 1,
        explicitAuthorizationRequired: false,
        reason: '',
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
    <div className="user-access-level-page">
      <PageHeader
        title="Cấu hình Mặc định theo Loại Khu vực"
        subtitle="Quản lý cấp độ truy cập mặc định cho từng loại khu vực trong toàn bộ khuôn viên."
        actions={
          <Button
            variant="outline"
            size="sm"
            onClick={loadPresets}
            disabled={loadingPresets}
            leftIcon={<RotateCcw size={16} className={loadingPresets ? 'animate-spin' : ''} />}
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
            <div className="loading-state">
              <Loader2 size={32} className="animate-spin" />
              <span>Đang tải cấu hình mặc định...</span>
            </div>
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
                            leftIcon={<Edit3 size={15} />}
                          >
                            Chỉnh sửa
                          </Button>
                        </td>
                      </tr>
                    );
                  })}
                  {presets.length === 0 && (
                    <tr>
                      <td colSpan={5} className="empty-state-cell">
                        Không có dữ liệu cấu hình mặc định nào.
                      </td>
                    </tr>
                  )}
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
                  <label className="modal-label">Yêu cầu chỉ định (Explicit Authorization)</label>
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
                  <label className="modal-label">
                    Lý do thay đổi <span className="text-danger">*</span>
                  </label>
                  <textarea
                    className="form-control textarea"
                    rows={3}
                    placeholder="Nhập lý do điều chỉnh cấu hình mặc định (từ 10 đến 500 ký tự)..."
                    value={editPresetModal.reason}
                    onChange={(e) =>
                      setEditPresetModal((prev) => ({ ...prev, reason: e.target.value }))
                    }
                    disabled={editPresetModal.isSaving}
                  />
                  <div className="field-char-count">
                    {editPresetModal.reason.length}/500 ký tự (tối thiểu 10)
                  </div>
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
                    leftIcon={
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
