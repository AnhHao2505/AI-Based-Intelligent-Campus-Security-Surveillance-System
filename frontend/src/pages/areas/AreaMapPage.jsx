import { useState, useEffect } from 'react';
import { Layers, AlertCircle, RotateCcw, Loader2 } from 'lucide-react';
import { getFloorPlans } from '../../services/areaService';
import './AreaMapPage.css';

export default function AreaMapPage() {
  const [floorPlans, setFloorPlans] = useState([]);
  const [selectedPlan, setSelectedPlan] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [imageError, setImageError] = useState(false);

  const sortFloorPlans = (plans) => {
    return [...plans].sort((a, b) => {
      const isNumA = /^\d+$/.test(a.floor);
      const isNumB = /^\d+$/.test(b.floor);
      if (!isNumA && isNumB) return -1;
      if (isNumA && !isNumB) return 1;
      if (!isNumA && !isNumB) return a.floor.localeCompare(b.floor);
      return parseInt(a.floor, 10) - parseInt(b.floor, 10);
    });
  };

  const loadFloorPlans = async () => {
    setLoading(true);
    setError(null);
    setImageError(false);
    try {
      const data = await getFloorPlans();
      const activePlans = (data || []).filter((fp) => fp.isActive !== false);
      const sorted = sortFloorPlans(activePlans);
      setFloorPlans(sorted);
      if (sorted.length > 0) {
        setSelectedPlan(sorted[0]);
      } else {
        setSelectedPlan(null);
      }
    } catch (err) {
      console.error('Failed to load floor plans:', err);
      setError(err.message || 'Lỗi tải sơ đồ tầng.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadFloorPlans();
  }, []);

  const handleSelectFloor = (plan) => {
    setSelectedPlan(plan);
    setImageError(false);
  };

  return (
    <div className="area-map-page">
      <div className="area-map-page__header">
        <div>
          <h1 className="area-map-page__title">Bản đồ khu vực</h1>
          <p className="area-map-page__subtitle">Xem và quản lý sơ đồ các tầng</p>
        </div>
        {selectedPlan && (
          <div className="area-map-page__meta">
            <span className="area-map-page__building-tag">{selectedPlan.building}</span>
            <span className="area-map-page__dim-tag">
              {selectedPlan.originalWidth} × {selectedPlan.originalHeight}
            </span>
          </div>
        )}
      </div>

      {loading && (
        <div className="area-map-page__state">
          <Loader2 className="area-map-page__spinner" size={32} />
          <span>Đang tải sơ đồ tầng...</span>
        </div>
      )}

      {!loading && error && (
        <div className="area-map-page__state area-map-page__state--error">
          <AlertCircle size={32} />
          <span className="area-map-page__error-text">{error}</span>
          <button type="button" className="area-map-page__btn-retry" onClick={loadFloorPlans}>
            <RotateCcw size={16} />
            <span>Thử lại</span>
          </button>
        </div>
      )}

      {!loading && !error && floorPlans.length === 0 && (
        <div className="area-map-page__state area-map-page__state--empty">
          <Layers size={36} />
          <span>Chưa có sơ đồ tầng nào</span>
        </div>
      )}

      {!loading && !error && floorPlans.length > 0 && selectedPlan && (
        <div className="area-map-page__content">
          <div className="area-map-page__tabs">
            {floorPlans.map((fp) => {
              const isActive = selectedPlan.id === fp.id;
              return (
                <button
                  key={fp.id}
                  type="button"
                  className={`area-map-page__tab ${isActive ? 'area-map-page__tab--active' : ''}`}
                  onClick={() => handleSelectFloor(fp)}
                >
                  <Layers size={16} />
                  <span>Tầng {fp.floor}</span>
                </button>
              );
            })}
          </div>

          <div className="area-map-page__viewport-card">
            <div className="area-map-page__image-wrapper">
              {imageError ? (
                <div className="area-map-page__image-fallback">
                  <AlertCircle size={28} />
                  <span>Không tải được ảnh: {selectedPlan.imageKey}</span>
                </div>
              ) : (
                <img
                  src={`/floor-plans/${selectedPlan.imageKey}`}
                  alt={`Sơ đồ tầng ${selectedPlan.floor}`}
                  className="area-map-page__image"
                  onError={() => setImageError(true)}
                />
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
