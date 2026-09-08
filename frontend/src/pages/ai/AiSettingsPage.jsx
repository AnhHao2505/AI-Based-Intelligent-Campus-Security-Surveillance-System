import { useState, useEffect } from 'react';
import { Cpu, Save, Loader2, CheckCircle2, AlertCircle } from 'lucide-react';
import { getAiConfig, updateAiConfig } from '../../services/aiConfigService';
import '../../styles/AiSettingsPage.css';

const DEFAULT_THRESHOLD = 0.75;
const DEFAULT_FPS = 15;

export default function AiSettingsPage() {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [successMsg, setSuccessMsg] = useState(null);

  const [threshold, setThreshold] = useState(DEFAULT_THRESHOLD);
  const [fps, setFps] = useState(DEFAULT_FPS);
  const [updatedAt, setUpdatedAt] = useState(null);

  // Track initial server values for change detection
  const [initialConfig, setInitialConfig] = useState({
    threshold: DEFAULT_THRESHOLD,
    fps: DEFAULT_FPS
  });

  useEffect(() => {
    fetchConfig();
  }, []);

  const fetchConfig = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getAiConfig();
      if (data) {
        const loadedThreshold = data.faceMatchThreshold ?? DEFAULT_THRESHOLD;
        const loadedFps = data.inferenceFps ?? DEFAULT_FPS;
        setThreshold(loadedThreshold);
        setFps(loadedFps);
        setUpdatedAt(data.updatedAt);
        setInitialConfig({
          threshold: loadedThreshold,
          fps: loadedFps
        });
      }
    } catch (err) {
      console.error('Lỗi khi tải cấu hình AI:', err);
      setError('Không thể kết nối đến máy chủ để lấy cấu hình AI.');
    } finally {
      setLoading(false);
    }
  };

  const handleResetDefault = () => {
    setThreshold(DEFAULT_THRESHOLD);
    setFps(DEFAULT_FPS);
    setError(null);
    setSuccessMsg(null);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setSuccessMsg(null);

    try {
      const payload = {
        faceMatchThreshold: parseFloat(threshold),
        inferenceFps: parseInt(fps, 10),
      };

      const res = await updateAiConfig(payload);
      setSuccessMsg('Đã lưu cấu hình AI toàn hệ thống thành công!');
      if (res && res.updatedAt) {
        setUpdatedAt(res.updatedAt);
      }
      setInitialConfig({
        threshold: parseFloat(threshold),
        fps: parseInt(fps, 10)
      });
    } catch (err) {
      console.error('Lỗi khi lưu cấu hình AI:', err);
      setError(err.message || 'Cập nhật cấu hình AI thất bại.');
    } finally {
      setSaving(false);
    }
  };

  // Change detection
  const hasChanges =
    parseFloat(threshold) !== parseFloat(initialConfig.threshold) ||
    parseInt(fps, 10) !== parseInt(initialConfig.fps, 10);

  // Calculate filled slider percentage (range: 0.50 - 0.95)
  const fillPercent = Math.max(
    0,
    Math.min(100, ((parseFloat(threshold) - 0.50) / (0.95 - 0.50)) * 100)
  );

  if (loading) {
    return (
      <div className="ai-settings-page">
        <div className="ai-settings-loading">
          <Loader2 className="animate-spin ai-settings-spinner" size={40} />
          <p>Đang tải cấu hình AI hệ thống...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="ai-settings-page">
      {/* Page Header */}
      <div className="ai-settings-header">
        <h1>
          <Cpu size={24} className="ai-settings-header__icon" />
          <span>Thiết Lập AI Hệ Thống</span>
        </h1>
        <p>Cấu hình tập trung thông số nhận diện khuôn mặt và tốc độ xử lý cho tất cả camera toàn trường.</p>
      </div>

      {/* Main Settings Card */}
      <div className="ai-settings-card">
        <form onSubmit={handleSubmit} className="ai-settings-form">
          <div className="ai-settings-card__body">
            {error && (
              <div className="ai-alert-banner ai-alert-banner--error">
                <AlertCircle size={18} />
                <span>{error}</span>
              </div>
            )}

            {successMsg && (
              <div className="ai-alert-banner ai-alert-banner--success">
                <CheckCircle2 size={18} />
                <span>{successMsg}</span>
              </div>
            )}

            {/* Setting 1: Face Match Threshold */}
            <div className="ai-setting-block">
              <div className="ai-setting-block__header">
                <label htmlFor="ai-threshold-slider" className="ai-setting-block__label">
                  Ngưỡng Nhận Diện Khuôn Mặt (Face Match Threshold)
                </label>
                <span className="ai-setting-block__badge">
                  {parseFloat(threshold).toFixed(2)}
                </span>
              </div>
              <p className="ai-setting-block__hint">
                Giá trị từ 0.50 đến 0.95. Ngưỡng càng cao đòi hỏi độ chính xác càng cao, giúp giảm cảnh báo giả nhưng có thể bỏ sót khi ánh sáng yếu. Mặc định khuyến nghị: 0.75.
              </p>
              <div className="ai-slider-wrapper">
                <input
                  id="ai-threshold-slider"
                  type="range"
                  className="ai-slider"
                  min="0.50"
                  max="0.95"
                  step="0.01"
                  value={threshold}
                  onChange={(e) => setThreshold(e.target.value)}
                  style={{
                    '--slider-fill': `${fillPercent}%`
                  }}
                />
                <div className="ai-slider-range-labels">
                  <span>0.50</span>
                  <span>0.95</span>
                </div>
              </div>
            </div>

            {/* Divider between setting blocks */}
            <hr className="ai-settings-divider" />

            {/* Setting 2: Inference FPS */}
            <div className="ai-setting-block">
              <label htmlFor="ai-fps-input" className="ai-setting-block__label">
                Số Khung Hình Xử Lý Mới Mỗi Giây (Inference FPS)
              </label>
              <p className="ai-setting-block__hint">
                Số lượng khung hình (frames) gửi cho mô hình AI phân tích trong 1 giây. FPS cao tăng khả năng phát hiện liên tục nhưng ngốn thêm tài nguyên Server. Mặc định: 15 FPS.
              </p>
              <div className="ai-fps-input-group">
                <input
                  id="ai-fps-input"
                  type="number"
                  className="ai-fps-input"
                  min="1"
                  max="60"
                  value={fps}
                  onChange={(e) => setFps(e.target.value)}
                  required
                />
                <span className="ai-fps-unit">khung/giây</span>
              </div>
            </div>
          </div>

          {/* Footer Strip */}
          <div className="ai-settings-card__footer">
            {updatedAt ? (
              <span className="ai-settings-updated-at">
                Lần cập nhật gần nhất: {new Date(updatedAt).toLocaleString('vi-VN')}
              </span>
            ) : (
              <span />
            )}
            <div className="ai-settings-actions">
              <button
                type="button"
                className="ai-btn-ghost"
                onClick={handleResetDefault}
              >
                Khôi phục mặc định
              </button>
              <div
                className="ai-save-btn-wrapper"
                title={!hasChanges ? 'Chưa có thay đổi nào' : ''}
              >
                <button
                  type="submit"
                  className="ai-btn-primary"
                  disabled={!hasChanges || saving}
                >
                  {saving ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />}
                  <span>{saving ? 'Đang lưu...' : 'Lưu Thay Đổi Cấu Hình'}</span>
                </button>
              </div>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
}

