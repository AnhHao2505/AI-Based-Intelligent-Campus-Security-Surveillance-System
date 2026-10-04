import React from "react";
import { AlertTriangle, ArrowRight, Ban, CheckCircle2 } from "lucide-react";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import { AREA_LEVEL_CONFIG, formatDisplayDateTime } from "../../utils/areaHelpers";
import "../../styles/AreaTypeChangePreviewModal.css";

const levelName = (level) => AREA_LEVEL_CONFIG[level]?.name || level || "—";
const accessText = (level, explicit) =>
	`Cấp ${level ?? "—"} · ${explicit ? "bắt buộc chỉ định / đơn được duyệt" : "không bắt buộc chỉ định"}`;

function RequestList({ title, items }) {
	return (
		<div className="atcp-section">
			<div className="atcp-section__title">
				{title} ({items?.length || 0})
			</div>
			{items && items.length > 0 ? (
				<ul className="atcp-list">
					{items.map((r) => (
						<li key={r.id} className="atcp-list__item">
							<span className="atcp-list__who">
								{r.requesterName || "—"} {r.requesterCode ? `(${r.requesterCode})` : ""}
								{r.requestType === "GROUP" ? " · đơn nhóm" : ""}
							</span>
							<span className="atcp-list__time">
								{formatDisplayDateTime(r.startTime)} → {formatDisplayDateTime(r.endTime)}
							</span>
						</li>
					))}
				</ul>
			) : (
				<div className="atcp-empty">Không có</div>
			)}
		</div>
	);
}

/**
 * BL2 — xem trước tác động đổi loại khu vực (GET /api/areas/{id}/type-change-preview).
 * Có lý do chặn -> khoá nút xác nhận. Xác nhận -> PUT như luồng sửa hiện tại (backend đánh giá lại sau khi khoá).
 */
export default function AreaTypeChangePreviewModal({ isOpen, areaName, preview, confirming, onConfirm, onClose }) {
	if (!preview) return null;
	const blocked = (preview.blockingReasons || []).length > 0;
	const toPublic = preview.newAreaLevel === "PUBLIC";

	return (
		<Modal
			isOpen={isOpen}
			onClose={confirming ? () => {} : onClose}
			title="Xem trước đổi loại khu vực"
			subtitle={areaName}
			icon={AlertTriangle}
			iconVariant={blocked ? "danger" : "warning"}
			size="lg"
			closeOnBackdrop={!confirming}
			footer={
				<>
					<Button variant="secondary" onClick={onClose} disabled={confirming}>
						Quay lại
					</Button>
					<Button variant={blocked ? "secondary" : "danger"} onClick={onConfirm} loading={confirming} disabled={blocked || confirming}>
						Xác nhận đổi loại
					</Button>
				</>
			}
		>
			<div className="atcp">
				<div className="atcp-change">
					<div className="atcp-change__side">
						<div className="atcp-change__label">Hiện tại</div>
						<div className="atcp-change__level">{levelName(preview.currentAreaLevel)}</div>
						<div className="atcp-change__meta">
							{accessText(preview.currentAreaAccessLevel, preview.currentExplicitAuthorizationRequired)}
						</div>
					</div>
					<ArrowRight size={18} className="atcp-change__arrow" />
					<div className="atcp-change__side">
						<div className="atcp-change__label">Sau khi đổi (áp mặc định của loại mới)</div>
						<div className="atcp-change__level">{levelName(preview.newAreaLevel)}</div>
						<div className="atcp-change__meta">
							{accessText(preview.newAreaAccessLevel, preview.newExplicitAuthorizationRequired)}
						</div>
					</div>
				</div>

				{blocked ? (
					<div className="atcp-section">
						<div className="atcp-section__title atcp-section__title--danger">Không thể đổi loại</div>
						<ul className="atcp-list">
							{preview.blockingReasons.map((b) => (
								<li key={b.code} className="atcp-alert atcp-alert--danger">
									<Ban size={15} />
									<span>
										<strong>{b.code}</strong> — {b.message}
									</span>
								</li>
							))}
						</ul>
					</div>
				) : (
					<div className="atcp-alert atcp-alert--ok">
						<CheckCircle2 size={15} />
						<span>Không có lý do chặn. Hệ thống sẽ kiểm lại lần nữa khi lưu.</span>
					</div>
				)}

				<div className="atcp-facts">
					<div className="atcp-fact">
						<span>Quyền chỉ định (AP) còn hiệu lực</span>
						<strong>{preview.activeAssignedPersonnelCount || "Không có"}</strong>
					</div>
					<div className="atcp-fact">
						<span>Chế độ sự kiện</span>
						<strong>{preview.eventActive ? "Đang mở" : "Không mở"}</strong>
					</div>
					<div className="atcp-fact">
						<span>Lịch sự kiện đang chờ</span>
						<strong>{preview.pendingScheduleCount || "Không có"}</strong>
					</div>
					{!toPublic && (
						<div className="atcp-fact">
							<span>Đơn PENDING sẽ không duyệt được</span>
							<strong>{preview.pendingRequestsNotApprovableCount || "Không có"}</strong>
						</div>
					)}
				</div>

				<RequestList title="Đơn đã duyệt (APPROVED) sẽ bị hệ thống huỷ" items={preview.approvedRequestsToCancel} />
				{toPublic && <RequestList title="Đơn chờ duyệt (PENDING) sẽ bị hệ thống huỷ" items={preview.pendingRequestsToCancel} />}
			</div>
		</Modal>
	);
}
