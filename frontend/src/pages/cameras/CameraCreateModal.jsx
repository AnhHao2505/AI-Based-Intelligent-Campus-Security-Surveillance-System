import { useState } from 'react';
import { X, Loader } from 'lucide-react';
import { createCamera } from '../../services/cameraService';
import '../../styles/CameraCreateModal.css';

export default function CameraCreateModal({ isOpen, onClose, onSuccess }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [formData, setFormData] = useState({
    cameraCode: '',
    name: '',
    floor: '',
    zoneName: '',
    x: '',
    y: '',
    mountingHeight: '',
    orientation: '',
    tiltAngle: '',
  });

  if (!isOpen) return null;

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setError(null);

    // Prepare payload (convert numbers)
    const payload = {
      name: formData.name,
      cameraCode: formData.cameraCode || undefined,
      floor: formData.floor ? parseInt(formData.floor, 10) : undefined,
      zoneName: formData.zoneName || undefined,
      x: formData.x ? parseFloat(formData.x) : undefined,
      y: formData.y ? parseFloat(formData.y) : undefined,
      mountingHeight: formData.mountingHeight ? parseFloat(formData.mountingHeight) : undefined,
      orientation: formData.orientation ? parseFloat(formData.orientation) : undefined,
      tiltAngle: formData.tiltAngle ? parseFloat(formData.tiltAngle) : undefined,
    };

    try {
      const response = await createCamera(payload);
      onSuccess(response);
      onClose();
    } catch (err) {
      console.error('Failed to create camera:', err);
      setError(err.message || 'Lỗi khi tạo camera. Vui lòng kiểm tra lại thông tin.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay">
      <div className="modal-container">
        <div className="modal-header">
          <h2>Thêm Camera Mới</h2>
          <button className="modal-close" onClick={onClose} disabled={loading}>
            <X size={20} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="modal-form">
          {error && <div className="modal-error">{error}</div>}

          <div className="form-grid">
            <div className="form-group col-span-2">
              <label htmlFor="name">Tên Camera <span className="required">*</span></label>
              <input
                type="text"
                id="name"
                name="name"
                value={formData.name}
                onChange={handleChange}
                placeholder="Ví dụ: Camera Cổng Chính A"
                required
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="cameraCode">Mã Camera</label>
              <input
                type="text"
                id="cameraCode"
                name="cameraCode"
                value={formData.cameraCode}
                onChange={handleChange}
                placeholder="Tự động sinh nếu bỏ trống"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="floor">Tầng</label>
              <input
                type="number"
                id="floor"
                name="floor"
                value={formData.floor}
                onChange={handleChange}
                placeholder="Ví dụ: 1"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="zoneName">Khu vực (Zone)</label>
              <input
                type="text"
                id="zoneName"
                name="zoneName"
                value={formData.zoneName}
                onChange={handleChange}
                placeholder="Ví dụ: Sảnh tòa nhà"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="mountingHeight">Độ cao lắp đặt (m)</label>
              <input
                type="number"
                step="0.1"
                id="mountingHeight"
                name="mountingHeight"
                value={formData.mountingHeight}
                onChange={handleChange}
                placeholder="Ví dụ: 3.5"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="x">Toạ độ X (Pixel)</label>
              <input
                type="number"
                step="0.01"
                id="x"
                name="x"
                value={formData.x}
                onChange={handleChange}
                placeholder="X trên bản đồ"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="y">Toạ độ Y (Pixel)</label>
              <input
                type="number"
                step="0.01"
                id="y"
                name="y"
                value={formData.y}
                onChange={handleChange}
                placeholder="Y trên bản đồ"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="orientation">Góc quay (độ)</label>
              <input
                type="number"
                step="0.1"
                id="orientation"
                name="orientation"
                value={formData.orientation}
                onChange={handleChange}
                placeholder="Ví dụ: 180"
                disabled={loading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="tiltAngle">Góc nghiêng (độ)</label>
              <input
                type="number"
                step="0.1"
                id="tiltAngle"
                name="tiltAngle"
                value={formData.tiltAngle}
                onChange={handleChange}
                placeholder="Ví dụ: -15"
                disabled={loading}
              />
            </div>
          </div>

          <div className="modal-actions">
            <button type="button" className="btn-secondary" onClick={onClose} disabled={loading}>
              Huỷ
            </button>
            <button type="submit" className="btn-primary" disabled={loading}>
              {loading ? <Loader className="animate-spin" size={16} /> : 'Tạo camera'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
