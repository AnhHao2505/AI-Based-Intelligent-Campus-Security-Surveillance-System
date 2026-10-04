import React, { useCallback, useEffect, useMemo, useState, useRef } from "react";
import {
	UserPlus,
	RefreshCw,
	Eye,
	Ban,
	Plus,
	Trash2,
	AlertCircle,
	ChevronLeft,
	ChevronRight,
	Users,
	Inbox,
} from "lucide-react";
import Modal from "../../components/ui/Modal";
import Button from "../../components/ui/Button";
import Badge from "../../components/ui/Badge";
import PageHeader from "../../components/ui/PageHeader";
import ReasonTextarea from "../../components/ui/ReasonTextarea";
import guestVisitService from "../../services/guestVisitService";
import { getLevelConfig } from "../../utils/areaHelpers";
import {
	getGuestVisitStatus,
	getGuestBiometricStatus,
	GUEST_ALLOWED_AREA_LEVELS,
	formatDateTime,
	canHostCancel,
} from "../../utils/guestHelpers";
import "../../styles/GuestVisitPage.css";

const PAGE_SIZE = 10;
const EMPTY_GUEST = { fullName: "", organization: "" };

function toIso(localValue) {
	// datetime-local (giờ máy người dùng) -> ISO UTC; backend nhận OffsetDateTime
	return localValue ? new Date(localValue).toISOString() : null;
}

function ReasonBlock({ label, value }) {
	if (!value) return null;
	return (
		<div className="guest-visit__reason">
			<span className="guest-visit__reason-label">{label}</span>
			<span>{value}</span>
		</div>
	);
}

export default function GuestVisitPage() {
	// Danh sách
	const [visits, setVisits] = useState([]);
	const [page, setPage] = useState(0);
	const [totalPages, setTotalPages] = useState(0);
	const [loading, setLoading] = useState(false);
	const [listError, setListError] = useState("");

	// Tạo lượt
	const [createOpen, setCreateOpen] = useState(false);
	const [areas, setAreas] = useState([]);
	const [loadingAreas, setLoadingAreas] = useState(false);
	const [purpose, setPurpose] = useState("");
	const [startTime, setStartTime] = useState("");
	const [endTime, setEndTime] = useState("");
	const [areaIds, setAreaIds] = useState([]);
	const [guests, setGuests] = useState([{ ...EMPTY_GUEST }]);
	const [creating, setCreating] = useState(false);
	const [createError, setCreateError] = useState("");

	// Chi tiết / huỷ
	const [detail, setDetail] = useState(null);
	const [cancelTarget, setCancelTarget] = useState(null);
	const [cancelReason, setCancelReason] = useState("");
	const [cancelReasonError, setCancelReasonError] = useState(null);
	const cancelReasonRef = useRef(null);
	const [cancelling, setCancelling] = useState(false);
	const [cancelError, setCancelError] = useState("");

	const loadVisits = useCallback(async (targetPage = 0) => {
		setLoading(true);
		setListError("");
		try {
			const data = await guestVisitService.getMyVisits({ page: targetPage, size: PAGE_SIZE });
			setVisits(data?.content || []);
			setTotalPages(data?.totalPages || 0);
			setPage(data?.number ?? targetPage);
		} catch (err) {
			setListError(err?.message || "Không tải được danh sách lượt khách.");
		} finally {
			setLoading(false);
		}
	}, []);

	useEffect(() => {
		loadVisits(0);
	}, [loadVisits]);

	const selectableAreas = useMemo(
		() => areas.filter((a) => GUEST_ALLOWED_AREA_LEVELS.includes(a.areaLevel)),
		[areas],
	);

	const openCreate = async () => {
		setPurpose("");
		setStartTime("");
		setEndTime("");
		setAreaIds([]);
		setGuests([{ ...EMPTY_GUEST }]);
		setCreateError("");
		setCreateOpen(true);
		setLoadingAreas(true);
		try {
			const data = await guestVisitService.getSelectableAreas();
			setAreas(Array.isArray(data) ? data : []);
		} catch (err) {
			setCreateError(err?.message || "Không tải được danh sách khu vực.");
		} finally {
			setLoadingAreas(false);
		}
	};

	const toggleArea = (id) => {
		setAreaIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
	};

	const updateGuest = (index, field, value) => {
		setGuests((prev) => prev.map((g, i) => (i === index ? { ...g, [field]: value } : g)));
	};

	const addGuest = () => setGuests((prev) => [...prev, { ...EMPTY_GUEST }]);
	const removeGuest = (index) => setGuests((prev) => prev.filter((_, i) => i !== index));

	const purposeLength = purpose.trim().length;
	const canSubmitCreate =
		!creating &&
		purposeLength >= 10 &&
		purposeLength <= 500 &&
		startTime &&
		endTime &&
		areaIds.length > 0 &&
		guests.length > 0 &&
		guests.every((g) => g.fullName.trim().length >= 2);

	const submitCreate = async () => {
		setCreating(true);
		setCreateError("");
		try {
			await guestVisitService.createVisit({
				purpose: purpose.trim(),
				startTime: toIso(startTime),
				endTime: toIso(endTime),
				areaIds,
				guests: guests.map((g) => ({
					fullName: g.fullName.trim(),
					organization: g.organization.trim() || null,
				})),
			});
			setCreateOpen(false);
			await loadVisits(0);
		} catch (err) {
			// Thông điệp nghiệp vụ lấy nguyên từ backend (ERR_GUEST_0xx)
			setCreateError(err?.message || "Không tạo được lượt khách.");
		} finally {
			setCreating(false);
		}
	};

	const openCancel = (visit) => {
		setCancelTarget(visit);
		setCancelReason("");
		setCancelReasonError(null);
		setCancelError("");
	};

	const submitCancel = async () => {
		if (!cancelTarget) return;
		const trimmed = cancelReason.trim();
		if (trimmed.length > 0 && (trimmed.length < 10 || trimmed.length > 500)) {
			setCancelReasonError(`Nếu nhập, lý do phải từ 10 đến 500 ký tự (hiện có ${trimmed.length}).`);
			cancelReasonRef.current?.focus();
			return;
		}
		setCancelling(true);
		setCancelError("");
		setCancelReasonError(null);
		try {
			await guestVisitService.cancelVisit(cancelTarget.id, {
				version: cancelTarget.version,
				reason: trimmed,
			});
			setCancelTarget(null);
			setCancelReason("");
			setCancelReasonError(null);
			setDetail(null);
			await loadVisits(page);
		} catch (err) {
			if (err?.code === "ERR_GUEST_016") {
				setCancelError("Lượt khách đã được người khác cập nhật. Danh sách đã được tải lại, vui lòng kiểm tra rồi thử lại.");
				setCancelTarget(null);
				setCancelReason("");
				setCancelReasonError(null);
				await loadVisits(page);
			} else {
				setCancelError(err?.message || "Không huỷ được lượt khách.");
			}
		} finally {
			setCancelling(false);
		}
	};

	const renderStatus = (status) => {
		const cfg = getGuestVisitStatus(status);
		return <Badge variant={cfg.variant}>{cfg.label}</Badge>;
	};

	return (
		<div className="guest-visit">
			<PageHeader
				title="Khách của tôi"
				description="Đăng ký lượt khách vào khu vực hạn chế. Quản lý cơ sở duyệt, quản trị viên gắn ảnh khách sau khi khách đồng ý tại quầy."
				actions={
					<>
						<Button variant="secondary" icon={RefreshCw} onClick={() => loadVisits(page)} loading={loading}>
							Tải lại
						</Button>
						<Button icon={UserPlus} onClick={openCreate}>
							Tạo lượt khách
						</Button>
					</>
				}
			/>

			{listError && (
				<div className="guest-visit__alert guest-visit__alert--danger">
					<AlertCircle size={16} />
					<span>{listError}</span>
				</div>
			)}
			{cancelError && !cancelTarget && (
				<div className="guest-visit__alert guest-visit__alert--warning">
					<AlertCircle size={16} />
					<span>{cancelError}</span>
				</div>
			)}

			<div className="guest-visit__card">
				{visits.length === 0 && !loading ? (
					<div className="guest-visit__empty">
						<Inbox size={28} />
						<span>Bạn chưa có lượt khách nào.</span>
					</div>
				) : (
					<table className="guest-visit__table">
						<thead>
							<tr>
								<th>Khung giờ</th>
								<th>Khu vực</th>
								<th>Khách</th>
								<th>Trạng thái</th>
								<th aria-label="Thao tác" />
							</tr>
						</thead>
						<tbody>
							{visits.map((v) => (
								<tr key={v.id}>
									<td>
										<div>{formatDateTime(v.startTime)}</div>
										<div className="guest-visit__muted">→ {formatDateTime(v.endTime)}</div>
									</td>
									<td>{(v.areas || []).map((a) => a.name).join(", ")}</td>
									<td>
										<span className="guest-visit__count">
											<Users size={14} /> {(v.guests || []).length}
										</span>
									</td>
									<td>{renderStatus(v.status)}</td>
									<td className="guest-visit__actions">
										<Button variant="ghost" size="sm" icon={Eye} onClick={() => setDetail(v)}>
											Chi tiết
										</Button>
										{canHostCancel(v) && (
											<Button variant="ghost" size="sm" icon={Ban} onClick={() => openCancel(v)}>
												Huỷ
											</Button>
										)}
									</td>
								</tr>
							))}
						</tbody>
					</table>
				)}

				{totalPages > 1 && (
					<div className="guest-visit__pager">
						<Button
							variant="ghost"
							size="sm"
							icon={ChevronLeft}
							disabled={page <= 0 || loading}
							onClick={() => loadVisits(page - 1)}
						>
							Trước
						</Button>
						<span className="guest-visit__muted">
							Trang {page + 1}/{totalPages}
						</span>
						<Button
							variant="ghost"
							size="sm"
							icon={ChevronRight}
							disabled={page >= totalPages - 1 || loading}
							onClick={() => loadVisits(page + 1)}
						>
							Sau
						</Button>
					</div>
				)}
			</div>

			{/* Tạo lượt khách */}
			<Modal
				isOpen={createOpen}
				onClose={() => !creating && setCreateOpen(false)}
				title="Tạo lượt khách"
				subtitle="Chỉ nhập họ tên và đơn vị của khách. Hệ thống không nhận CCCD, số điện thoại, địa chỉ hay email."
				icon={UserPlus}
				size="lg"
				closeOnBackdrop={!creating}
				footer={
					<>
						<Button variant="secondary" onClick={() => setCreateOpen(false)} disabled={creating}>
							Đóng
						</Button>
						<Button onClick={submitCreate} loading={creating} disabled={!canSubmitCreate}>
							Gửi duyệt
						</Button>
					</>
				}
			>
				<fieldset className="guest-visit__form" disabled={creating}>
					{createError && (
						<div className="guest-visit__alert guest-visit__alert--danger">
							<AlertCircle size={16} />
							<span>{createError}</span>
						</div>
					)}

					<label className="guest-visit__field">
						<span className="guest-visit__label">Mục đích *</span>
						<textarea
							rows={3}
							value={purpose}
							onChange={(e) => setPurpose(e.target.value)}
							placeholder="Ví dụ: Đối tác doanh nghiệp tham quan phòng lab AI để bàn hợp tác đào tạo"
						/>
						<span className={`guest-visit__hint ${purposeLength > 0 && (purposeLength < 10 || purposeLength > 500) ? "guest-visit__hint--error" : ""}`}>
							{purposeLength}/500 ký tự (tối thiểu 10)
						</span>
					</label>

					<div className="guest-visit__row">
						<label className="guest-visit__field">
							<span className="guest-visit__label">Bắt đầu *</span>
							<input type="datetime-local" value={startTime} onChange={(e) => setStartTime(e.target.value)} />
						</label>
						<label className="guest-visit__field">
							<span className="guest-visit__label">Kết thúc *</span>
							<input type="datetime-local" value={endTime} onChange={(e) => setEndTime(e.target.value)} />
						</label>
					</div>

					<div className="guest-visit__field">
						<span className="guest-visit__label">Khu vực * (chỉ loại Bảo mật nội bộ hoặc Liên hệ trước)</span>
						{loadingAreas ? (
							<span className="guest-visit__muted">Đang tải khu vực…</span>
						) : selectableAreas.length === 0 ? (
							<span className="guest-visit__muted">Không có khu vực nào nhận khách.</span>
						) : (
							<div className="guest-visit__areas">
								{selectableAreas.map((a) => {
									const level = getLevelConfig(a.areaLevel);
									return (
										<label key={a.id} className="guest-visit__area">
											<input type="checkbox" checked={areaIds.includes(a.id)} onChange={() => toggleArea(a.id)} />
											<span>{a.name}</span>
											<span className="guest-visit__muted">{level.name}</span>
										</label>
									);
								})}
							</div>
						)}
						<span className="guest-visit__hint">
							Bạn phải có quyền vào khu vực đó trong suốt khung giờ (theo cấp truy cập hoặc nhân sự chỉ định).
						</span>
					</div>

					<div className="guest-visit__field">
						<span className="guest-visit__label">Danh sách khách *</span>
						{guests.map((g, i) => (
							<div className="guest-visit__guest-row" key={i}>
								<input
									placeholder="Họ tên (2–100 ký tự)"
									value={g.fullName}
									maxLength={100}
									onChange={(e) => updateGuest(i, "fullName", e.target.value)}
								/>
								<input
									placeholder="Đơn vị (không bắt buộc)"
									value={g.organization}
									maxLength={200}
									onChange={(e) => updateGuest(i, "organization", e.target.value)}
								/>
								<Button
									variant="ghost"
									size="sm"
									icon={Trash2}
									onClick={() => removeGuest(i)}
									disabled={guests.length <= 1}
									aria-label="Xoá khách"
								/>
							</div>
						))}
						<div>
							<Button variant="secondary" size="sm" icon={Plus} onClick={addGuest}>
								Thêm khách
							</Button>
						</div>
					</div>
				</fieldset>
			</Modal>

			{/* Chi tiết */}
			<Modal
				isOpen={!!detail}
				onClose={() => setDetail(null)}
				title="Chi tiết lượt khách"
				icon={Eye}
				size="lg"
				footer={
					<>
						{detail && canHostCancel(detail) && (
							<Button variant="danger" icon={Ban} onClick={() => openCancel(detail)}>
								Huỷ lượt
							</Button>
						)}
						<Button variant="secondary" onClick={() => setDetail(null)}>
							Đóng
						</Button>
					</>
				}
			>
				{detail && (
					<div className="guest-visit__detail">
						<div className="guest-visit__detail-row">
							<span className="guest-visit__label">Trạng thái</span>
							{renderStatus(detail.status)}
						</div>
						<div className="guest-visit__detail-row">
							<span className="guest-visit__label">Khung giờ</span>
							<span>
								{formatDateTime(detail.startTime)} → {formatDateTime(detail.endTime)}
							</span>
						</div>
						<div className="guest-visit__detail-row">
							<span className="guest-visit__label">Khu vực</span>
							<span>{(detail.areas || []).map((a) => a.name).join(", ")}</span>
						</div>
						<div className="guest-visit__detail-row">
							<span className="guest-visit__label">Mục đích</span>
							<span>{detail.purpose}</span>
						</div>
						<ReasonBlock label="Lý do từ chối / duyệt" value={detail.reviewReason} />
						<ReasonBlock label="Lý do thu hồi" value={detail.revokeReason} />
						<ReasonBlock label="Lý do huỷ" value={detail.cancelReason} />

						<table className="guest-visit__table">
							<thead>
								<tr>
									<th>Họ tên</th>
									<th>Đơn vị</th>
									<th>Ảnh khuôn mặt</th>
								</tr>
							</thead>
							<tbody>
								{(detail.guests || []).map((g) => {
									const bio = getGuestBiometricStatus(g.biometricStatus);
									return (
										<tr key={g.id}>
											<td>{g.fullName}</td>
											<td>{g.organization || "—"}</td>
											<td>
												<Badge variant={bio.variant}>{bio.label}</Badge>
											</td>
										</tr>
									);
								})}
							</tbody>
						</table>
					</div>
				)}
			</Modal>

			{/* Huỷ */}
			<Modal
				isOpen={!!cancelTarget}
				onClose={() => {
					if (!cancelling) {
						setCancelTarget(null);
						setCancelReasonError(null);
					}
				}}
				title="Huỷ lượt khách"
				subtitle={
					cancelTarget?.status === "APPROVED"
						? "Lượt đã được duyệt: huỷ sẽ thu hồi quyền của khách và xoá ngay dữ liệu khuôn mặt đã gắn."
						: "Lượt đang chờ duyệt sẽ bị huỷ."
				}
				icon={Ban}
				iconVariant="danger"
				closeOnBackdrop={!cancelling}
				footer={
					<>
						<Button
							variant="secondary"
							onClick={() => {
								setCancelTarget(null);
								setCancelReasonError(null);
							}}
							disabled={cancelling}
						>
							Đóng
						</Button>
						<Button variant="danger" onClick={submitCancel} loading={cancelling} disabled={cancelling}>
							Xác nhận huỷ
						</Button>
					</>
				}
			>
				{cancelError && cancelTarget && (
					<div className="guest-visit__alert guest-visit__alert--danger">
						<AlertCircle size={16} />
						<span>{cancelError}</span>
					</div>
				)}
				<ReasonTextarea
					ref={cancelReasonRef}
					label="Lý do (không bắt buộc)"
					placeholder="Nhập lý do huỷ lượt khách (nếu có)..."
					value={cancelReason}
					onChange={(e) => {
						setCancelReason(e.target.value);
						if (cancelReasonError && (e.target.value.trim().length === 0 || (e.target.value.trim().length >= 10 && e.target.value.trim().length <= 500))) {
							setCancelReasonError(null);
						}
					}}
					error={cancelReasonError}
					required={false}
					min={10}
					max={500}
					disabled={cancelling}
				/>
			</Modal>
		</div>
	);
}
