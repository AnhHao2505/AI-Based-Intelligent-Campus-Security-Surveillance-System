import { useState, useEffect } from 'react';
import { Cpu, Save, Loader2, CheckCircle2, AlertCircle } from 'lucide-react';
import { getAiConfig, updateAiConfig } from '../../services/aiConfigService';
import '../../styles/AiSettingsPage.css';

export default function AiSettingsPage() {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [successMsg, setSuccessMsg] = useState(null);

  const [threshold, setThreshold] = useState(0.75);
  const [fps, setFps] = useState(15);
  const [updatedAt, setUpdatedAt] = useState(null);

  useEffect(() => {
    fetchConfig();
  }, []);

  const fetchConfig = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getAiConfig();
      if (data) {
        setThreshold(data.faceMatchThreshold ?? 0.75);
        setFps(data.inferenceFps ?? 15);
        setUpdatedAt(data.updatedAt);
      }
    } catch (err) {
      console.error('Lỗi khi tải cấu hình AI:', err);
      setError('Không thể kết nối đến máy chủ để lấy cấu hình AI.');
    } finally {
      setLoading(false);
    }
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
    } catch (err) {
      console.error('Lỗi khi lưu cấu hình AI:', err);
      setError(err.message || 'Cập nhật cấu hình AI thất bại.');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="ai-settings-container">
        <div className="ai-loading-spinner">
          <Loader2 className="animate-spin text-blue" size={44} />
          <p>Đang tải cấu hình AI hệ thống...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="ai-settings-container">
      <div className="ai-settings-header">
        <h1>
          <Cpu size={28} className="text-blue" />
          Thiết Lập AI Hệ Thống
        </h1>
        <p>Cấu hình tập trung thông số nhận diện khuôn mặt và tốc độ xử lý cho tất cả camera toàn trường.</p>
      </div>

      <div className="ai-settings-card">
        {error && (
          <div className="ai-alert-banner error">
            <AlertCircle size={20} />
            <span>{error}</span>
          </div>
        )}

        {successMsg && (
          <div className="ai-alert-banner success">
            <CheckCircle2 size={20} />
            <span>{successMsg}</span>
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="ai-form-group">
            <label>Ngưỡng Nhận Diện Khuôn Mặt (Face Match Threshold)</label>
            <div className="slider-container">
              <input
                type="range"
                className="range-slider"
                min="0.50"
                max="0.95"
                step="0.01"
                value={threshold}
                onChange={(e) => setThreshold(e.target.value)}
              />
              <div className="slider-value-badge">{parseFloat(threshold).toFixed(2)}</div>
            </div>
            <p className="form-hint">
              Giá trị từ 0.50 đến 0.95. Ngưỡng càng cao đòi hỏi độ chính xác càng cao, giúp giảm cảnh báo giả nhưng có thể bỏ sót khi ánh sáng yếu. Mặc định khuyến nghị: 0.75.
            </p>
          </div>

          <div className="ai-form-group">
            <label>Số Khung Hình Xử Lý Mới Mỗi Giây (Inference FPS)</label>
            <input
              type="number"
              className="input-number-custom"
              min="1"
              max="60"
              value={fps}
              onChange={(e) => setFps(e.target.value)}
              required
            />
            <p className="form-hint">
              Số lượng khung hình (frames) gửi cho mô hình AI phân tích trong 1 giây. FPS cao tăng khả năng phát hiện liên tục nhưng ngốn thêm tài nguyên Server. Mặc định: 15 FPS.
            </p>
          </div>

          {updatedAt && (
            <p style={{ fontSize: '0.85rem', color: '#64748b', marginBottom: '1.5rem' }}>
              Lần cập nhật gần nhất: {new Date(updatedAt).toLocaleString('vi-VN')}
            </p>
          )}

          <button type="submit" className="btn-ai-save" disabled={saving}>
            {saving ? <Loader2 size={18} className="animate-spin" /> : <Save size={18} />}
            <span>{saving ? 'Đang lưu...' : 'Lưu Thay Đổi Cấu Hình'}</span>
          </button>
        </form>
      </div>
    </div>
  );
}
