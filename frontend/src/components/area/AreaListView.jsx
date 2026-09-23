import React from 'react';
import {
  AlertCircle,
  Plus,
  Cctv,
  VideoOff,
  ShieldCheck,
  Users,
  Pencil,
  Trash2,
} from 'lucide-react';
import { getLevelConfig } from '../../utils/areaHelpers';

export default function AreaListView({
  floorAreas,
  selectedFloor,
  selectedBuilding,
  selectedAreaId,
  cameraCounts,
  isAdmin,
  isFacilityManager,
  onSelectArea,
  onOpenCreateModal,
  onOpenCamerasModal,
  onOpenAccessRulesModal,
  onOpenAssignedPersonnelModal,
  onOpenEditModal,
  onOpenDeactivateModal,
}) {
  return (
    <div className="zone-list-layout">
      {floorAreas.length === 0 ? (
        <div className="area-empty-state">
          <div className="area-empty-state__icon">
            <AlertCircle size={28} />
          </div>
          <div className="area-empty-state__title">
            Chưa có khu vực nào trên Tầng {selectedFloor} (Tòa {selectedBuilding})
          </div>
          {isAdmin && (
            <button
              type="button"
              className="zone-toolbar__add-btn"
              style={{ marginTop: '12px' }}
              onClick={onOpenCreateModal}
            >
              <Plus size={16} />
              <span>Thêm khu vực đầu tiên</span>
            </button>
          )}
        </div>
      ) : (
        <div className="area-grid">
          {floorAreas.map((area) => {
            const isSelected = selectedAreaId === area.id;
            const levelKey = area.areaLevel || area.level?.code || area.level || 'PUBLIC';
            const levelConfig = getLevelConfig(levelKey);

            return (
              <div
                key={area.id}
                className={`zone-card ${levelConfig.cardClass} ${
                  isSelected ? 'zone-card--selected' : ''
                }`}
                onClick={() => onSelectArea(area.id)}
              >
                <div className="zone-card__header">
                  <div className="zone-card__badges">
                    <span className={`level-badge ${levelConfig.badgeClass}`}>
                      {levelConfig.badgeLabel}
                    </span>
                    <span
                      className="zone-card__pill-level"
                      title="Cấp độ người dùng tối thiểu để vào tự do"
                    >
                      Level {area.areaAccessLevel ?? 1}
                    </span>
                  </div>
                  <span
                    className={`zone-card__status-dot ${
                      area.isActive ? '' : 'zone-card__status-dot--inactive'
                    }`}
                    title={area.isActive ? 'Active' : 'Inactive'}
                  />
                </div>

                <h3 className="zone-card__title">{area.name}</h3>
                <div className="zone-card__code">{area.code}</div>

                <div className="zone-card__footer">
                  <div
                    className="zone-card__camera-status"
                    style={{ cursor: 'pointer' }}
                    onClick={(e) => {
                      e.stopPropagation();
                      onOpenCamerasModal(area);
                    }}
                    title="Xem danh sách camera"
                  >
                    {(() => {
                      const count = cameraCounts[area.id] ?? area.cameraCount ?? 0;
                      return count > 0 ? (
                        <span className="zone-card__camera-status--has">
                          <Cctv size={13} />
                          <span>Camera: {count}</span>
                        </span>
                      ) : (
                        <span className="zone-card__camera-status--none">
                          <VideoOff size={13} />
                          <span>Camera </span>
                        </span>
                      );
                    })()}
                  </div>

                  <div className="zone-card__quick-actions">
                    <button
                      type="button"
                      className="zone-card__quick-btn"
                      onClick={(e) => {
                        e.stopPropagation();
                        onOpenCamerasModal(area);
                      }}
                      title="Xem danh sách Camera gán"
                    >
                      <Cctv size={13} />
                    </button>

                    {isFacilityManager && (
                      <button
                        type="button"
                        className="zone-card__quick-btn"
                        onClick={(e) => {
                          e.stopPropagation();
                          onOpenAccessRulesModal(area);
                        }}
                        title="Cấu hình quy tắc truy cập"
                      >
                        <ShieldCheck size={13} />
                      </button>
                    )}

                    <button
                      type="button"
                      className="zone-card__quick-btn"
                      onClick={(e) => {
                        e.stopPropagation();
                        onOpenAssignedPersonnelModal(area);
                      }}
                      title="Xem nhân sự chỉ định cố định"
                    >
                      <Users size={13} />
                    </button>

                    {isAdmin && (
                      <>
                        <button
                          type="button"
                          className="zone-card__quick-btn"
                          onClick={(e) => {
                            e.stopPropagation();
                            onSelectArea(area.id);
                            onOpenEditModal();
                          }}
                          title="Sửa khu vực"
                        >
                          <Pencil size={13} />
                        </button>
                        <button
                          type="button"
                          className="zone-card__quick-btn zone-card__quick-btn--danger"
                          onClick={(e) => {
                            e.stopPropagation();
                            onSelectArea(area.id);
                            onOpenDeactivateModal();
                          }}
                          title="Vô hiệu hoá"
                        >
                          <Trash2 size={13} />
                        </button>
                      </>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
