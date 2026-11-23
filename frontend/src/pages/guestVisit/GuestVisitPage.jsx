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
	Camera,
} from "lucide-react";
import Modal from "../../components/ui/Modal";
import Button from "../../components/ui/Button";
import Badge from "../../components/ui/Badge";
import PageHeader from "../../components/ui/PageHeader";
import { LoadingState, EmptyState, ErrorState } from "../../components/ui";
import ReasonTextarea from "../../components/ui/ReasonTextarea";
import guestVisitService from "../../services/guestVisitService";
import { getLevelConfig } from "../../utils/areaHelpers";
import {
	getGuestVisitStatus,
	getGuestBiometricStatus,
	GUEST_ALLOWED_AREA_LEVELS,
	formatDateTime,
	canHostCancel,
	getMissingPhotoInfo,
	canHostReplacePhoto,
	getErrorGuestIndex,
} from "../../utils/guestHelpers";
import GuestPhotoPicker from "./GuestPhotoPicker";
import GuestPhotoThumb from "./GuestPhotoThumb";
import "../../styles/GuestVisitPage.css";
import { useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { useReasonLimits } from "../../hooks/useReasonLimits";
import useRevealMessage from "../../hooks/useRevealMessage";
import useGuestPurposeLimits from "../../hooks/useGuestPurposeLimits";
import useGuestFormLimits from "../../hooks/useGuestFormLimits";
import { guestPurposeState } from "../../utils/guestPurposeLimits";

const PAGE_SIZE = 10;
// A1: mỗi khách có ảnh khuôn mặt + xác nhận đồng ý; uid làm key ổn định khi xoá khách giữa danh sách
let guestSeq = 0;
const newGuest = () => ({ uid: ++guestSeq, fullName: "", organization: "", photo: { file: null, consent: false } });

function toIso(localValue) {
	// datetime-local (giờ máy người dùng) -> ISO UTC; backend nhận OffsetDateTime
	return localValue ? new Date(localValue).toISOString() : null;
}

// BR-GV-06: lý do khu vực không mời được trong khung giờ đã chọn (reasonCode của selectable-areas)
const AREA_BLOCK_REASONS = {
	ERR_GUEST_014: "Bạn không vào được khu này trong suốt khung giờ đã chọn",
	ERR_GUEST_002: "Tài khoản chưa đủ điều kiện mời khách",
};

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
	const reasonLimits = useReasonLimits();
	// N1 (V120): độ dài mục đích lượt khách đọc từ cấu hình (GUEST_PURPOSE_MIN_LENGTH / GUEST_PURPOSE_MAX_LENGTH),
	// đọc lại mỗi lần mở form tạo lượt; chưa đọc được thì không chặn độ dài, backend kiểm
	const [purposeLimits, reloadPurposeLimits] = useGuestPurposeLimits();
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
	const [guests, setGuests] = useState(() => [newGuest()]);
	const [creating, setCreating] = useState(false);
	const [createError, setCreateError] = useState("");
	// A2: backend báo lỗi ảnh của đúng một khách (data.guestIndex) -> tô khung khách đó
	const [errorGuestIndex, setErrorGuestIndex] = useState(null);
	// Banner lỗi ở đầu popup tạo lượt khách, nút Gửi duyệt ở chân popup -> cuộn banner vào tầm nhìn (LT-01)
	const createErrorRef = useRef(null);
	useRevealMessage(createErrorRef, createError);

	// Chi tiết / huỷ
	const [detail, setDetail] = useState(null);
	// Bấm thông báo -> ?visitId: tô sáng dòng lượt khách hoặc mở popup chi tiết
	const [searchParams, setSearchParams] = useSearchParams();
	const [visitsLoaded, setVisitsLoaded] = useState(false);
	const [highlightVisitId, setHighlightVisitId] = useState(null);
	const handledVisitIdRef = useRef(null);
	const [cancelTarget, setCancelTarget] = useState(null);
	const [cancelReason, setCancelReason] = useState("");
	const [cancelReasonError, setCancelReasonError] = useState(null);
	const cancelReasonRef = useRef(null);
	const [cancelling, setCancelling] = useState(false);
	const [cancelError, setCancelError] = useState("");

	// A5: thay ảnh khách khi lượt Chờ duyệt / Đã duyệt chưa tới giờ bắt đầu
	const [replaceTarget, setReplaceTarget] = useState(null); // { visit, guest }
	const [replacePhoto, setReplacePhoto] = useState({ file: null, consent: false });
	const [replacing, setReplacing] = useState(false);
	const [replaceError, setReplaceError] = useState("");

	const loadVisits = useCallback(async (targetPage = 0) => {
		setLoading(true);
		setListError("");
		try {
			const data = await guestVisitService.getMyVisits({ page: targetPage, size: PAGE_SIZE });
			setVisits(data?.content || []);
			setTotalPages(data?.totalPages || 0);
			setPage(data?.number ?? targetPage);
		} catch (err) {
			setVisits([]);
			setListError(err?.message || "Không tải được danh sách lượt khách.");
		} finally {
			setLoading(false);
			setVisitsLoaded(true);
		}
	}, []);

	useEffect(() => {
		loadVisits(0);
	}, [loadVisits]);

	// ?visitId (từ thông báo): lượt có trong danh sách đang hiện -> cuộn tới + tô sáng;
	// không có (khác trang) -> GET /api/guest-visits/{id} mở popup chi tiết; lỗi quyền / không thấy -> toast.
	useEffect(() => {
		const visitId = searchParams.get("visitId");
		if (!visitId || !visitsLoaded || loading) return;
		if (handledVisitIdRef.current === visitId) return;
		handledVisitIdRef.current = visitId;
		setSearchParams(
			(prev) => {
				const next = new URLSearchParams(prev);
				next.delete("visitId");
				return next;
			},
			{ replace: true },
		);
		if (visits.some((v) => v.id === visitId)) {
			setHighlightVisitId(visitId);
			requestAnimationFrame(() => {
				document
					.querySelector(`[data-visit-id="${visitId}"]`)
					?.scrollIntoView({ behavior: "smooth", block: "center" });
			});
			return;
		}
		guestVisitService
			.getVisit(visitId)
			.then((data) => {
				if (data) setDetail(data);
			})
			.catch(() => toast.error("Không xem được lượt khách này"));
	}, [searchParams, setSearchParams, visitsLoaded, loading, visits]);

	useEffect(() => {
		if (!highlightVisitId) return;
		const timer = setTimeout(() => setHighlightVisitId(null), 2000);
		return () => clearTimeout(timer);
	}, [highlightVisitId]);

	// B-04: điều kiện mời khách (cấp ≥ GUEST_HOST_MIN_LEVEL). Lỗi tải -> để null, vẫn hiện nút (BE vẫn chặn ERR_GUEST_002)
	const [eligibility, setEligibility] = useState(null);
	useEffect(() => {
		guestVisitService
			.getEligibility()
			.then((data) => setEligibility(data || null))
			.catch((err) => console.error("Lỗi tải điều kiện mời khách:", err));
	}, []);
	const cannotHost = eligibility?.canHost === false;
	// Phiên bản thông báo đồng ý + giới hạn ảnh lấy từ /eligibility (NORMAL_USER không đọc được System Configuration).
	// TC-F12-S07: đọc lại MỖI LẦN mở form tạo lượt / hộp gắn - thay ảnh (không dùng giá trị đọc lúc tải trang), để Admin
	// đổi GUEST_PHOTO_MAX_KB khi trang đang mở thì lần mở form sau dùng giá trị mới.
	// V141: đang đọc / đọc lỗi thì photoMaxKb null -> form không chặn dung lượng, backend kiểm (ERR_GUEST_033); không đoán số
	const [createLimits, reloadCreateLimits] = useGuestFormLimits();
	const [replaceLimits, reloadReplaceLimits] = useGuestFormLimits();
	// Cùng lời gọi /eligibility: cập nhật luôn canHost của trang (nút "Tạo lượt khách", cảnh báo chưa đủ cấp)
	const refreshEligibility = (data) => {
		if (data) setEligibility(data);
	};

	const selectableAreas = useMemo(
		() => areas.filter((a) => GUEST_ALLOWED_AREA_LEVELS.includes(a.areaLevel)),
		[areas],
	);

	const openCreate = async () => {
		setPurpose("");
		setStartTime("");
		setEndTime("");
		setAreaIds([]);
		setGuests([newGuest()]);
		setCreateError("");
		setErrorGuestIndex(null);
		setCreateOpen(true);
		reloadCreateLimits().then(refreshEligibility);
		reloadPurposeLimits();
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

	// BR-GV-06: đã chọn đủ giờ (bắt đầu < kết thúc) thì hỏi lại BE khu nào mời được trong khung giờ đó; đổi giờ thì tính lại.
	// Khu không mời được: hiện mờ, không tick được, bỏ tick nếu đang tick. Chưa đủ giờ thì bỏ đánh dấu.
	const areaWindowSeq = useRef(0);
	useEffect(() => {
		if (!createOpen) return undefined;
		const startIso = toIso(startTime);
		const endIso = toIso(endTime);
		const seq = ++areaWindowSeq.current;
		if (!startIso || !endIso || new Date(startIso) >= new Date(endIso)) {
			setAreas((prev) =>
				prev.some((a) => a.hostCanInvite !== undefined)
					? prev.map(({ hostCanInvite: _h, reasonCode: _r, ...rest }) => rest)
					: prev,
			);
			return undefined;
		}
		const timer = setTimeout(() => {
			guestVisitService
				.getSelectableAreas({ startTime: startIso, endTime: endIso })
				.then((data) => {
					if (seq !== areaWindowSeq.current) return;
					const list = Array.isArray(data) ? data : [];
					setAreas(list);
					const blocked = new Set(list.filter((a) => a.hostCanInvite === false).map((a) => a.id));
					setAreaIds((prev) => (prev.some((id) => blocked.has(id)) ? prev.filter((id) => !blocked.has(id)) : prev));
				})
				.catch((err) => console.error("Lỗi kiểm tra khu vực theo khung giờ:", err));
		}, 300);
		return () => clearTimeout(timer);
	}, [createOpen, startTime, endTime]);

	const toggleArea = (id) => {
		setAreaIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
	};

	const updateGuest = (index, field, value) => {
		setGuests((prev) => prev.map((g, i) => (i === index ? { ...g, [field]: value } : g)));
	};

	const addGuest = () => setGuests((prev) => [...prev, newGuest()]);
	const removeGuest = (index) => {
		setGuests((prev) => prev.filter((_, i) => i !== index));
		setErrorGuestIndex(null);
	};

	const purposeState = guestPurposeState(purpose, purposeLimits);
	// UX-03: lý do nút "Gửi duyệt" đang khoá — cùng điều kiện với canSubmitCreate
	const createBlockers = [];
	if (purposeState.blocker) createBlockers.push(purposeState.blocker);
	if (!startTime || !endTime) createBlockers.push("chọn thời gian bắt đầu và kết thúc");
	if (areaIds.length === 0) createBlockers.push("chọn ít nhất một khu vực");
	if (guests.length === 0 || !guests.every((g) => g.fullName.trim().length >= 2)) {
		createBlockers.push("nhập họ tên (tối thiểu 2 ký tự) cho mọi khách");
	}
	if (createLimits.status === "loading") {
		createBlockers.push("chờ tải phiên bản thông báo đồng ý");
	} else if (!createLimits.consentVersion) {
		createBlockers.push("tải được phiên bản thông báo đồng ý (thử đóng rồi mở lại form)");
	} else if (!guests.every((g) => g.photo.file && g.photo.consent)) {
		createBlockers.push("chọn ảnh và tick đồng ý chụp ảnh cho mọi khách");
	}
	const canSubmitCreate = !creating && createBlockers.length === 0;

	const submitCreate = async () => {
		setCreating(true);
		setCreateError("");
		setErrorGuestIndex(null);
		try {
			await guestVisitService.createVisit({
				purpose: purpose.trim(),
				startTime: toIso(startTime),
				endTime: toIso(endTime),
				areaIds,
				consentNoticeVersion: createLimits.consentVersion,
				guests: guests.map((g) => ({
					fullName: g.fullName.trim(),
					organization: g.organization.trim() || null,
					consentConfirmed: g.photo.consent,
				})),
			}, guests.map((g) => g.photo.file));
			setCreateOpen(false);
			await loadVisits(0);
		} catch (err) {
			// Thông điệp nghiệp vụ lấy nguyên từ backend (ERR_GUEST_0xx); lỗi ảnh nêu đúng khách
			setCreateError(err?.message || "Không tạo được lượt khách.");
			setErrorGuestIndex(getErrorGuestIndex(err));
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
		if (trimmed.length > 0 && !reasonLimits.isValid(trimmed)) {
			setCancelReasonError(`Nếu nhập, ${reasonLimits.error(trimmed, "lý do")}`);
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

	const openReplace = (visit, guest) => {
		setReplaceTarget({ visit, guest });
		setReplacePhoto({ file: null, consent: false });
		setReplaceError("");
		reloadReplaceLimits().then(refreshEligibility);
	};

	const closeReplace = () => {
		if (replacing) return;
		setReplaceTarget(null);
		setReplaceError("");
	};

	const submitReplace = async () => {
		const consentVersion = replaceLimits.consentVersion;
		if (!replaceTarget || !replacePhoto.file || !replacePhoto.consent || !consentVersion) return;
		setReplacing(true);
		setReplaceError("");
		try {
			await guestVisitService.uploadGuestPhoto(replaceTarget.visit.id, replaceTarget.guest.id, {
				file: replacePhoto.file,
				consentConfirmed: true,
				consentNoticeVersion: consentVersion,
			});
			toast.success(`Đã lưu ảnh cho khách ${replaceTarget.guest.fullName}`);
			setReplaceTarget(null);
			// thay ảnh tăng version lượt -> tải lại chi tiết + danh sách để thao tác sau dùng version mới
			const fresh = await guestVisitService.getVisit(replaceTarget.visit.id).catch(() => null);
			if (fresh) setDetail(fresh);
			await loadVisits(page);
		} catch (err) {
			setReplaceError(err?.message || "Không lưu được ảnh khách.");
		} finally {
			setReplacing(false);
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
				description="Đăng ký lượt khách vào khu vực hạn chế kèm ảnh khuôn mặt của từng khách (khách đã đồng ý chụp ảnh). Quản lý cơ sở vật chất xem ảnh và duyệt."
				actions={
					<>
						<Button variant="secondary" icon={RefreshCw} onClick={() => loadVisits(page)} loading={loading}>
							Tải lại
						</Button>
						{!cannotHost && (
							<Button icon={UserPlus} onClick={openCreate}>
								Tạo lượt khách
							</Button>
						)}
					</>
				}
			/>

			{cannotHost && (
				<div className="guest-visit__alert guest-visit__alert--warning" role="status">
					<AlertCircle size={16} />
					<span>
						Cần cấp truy cập ≥ {eligibility.minLevel} để mời khách (cấp hiện tại: {eligibility.myLevel ?? "—"})
					</span>
				</div>
			)}
			{cancelError && !cancelTarget && (
				<div className="guest-visit__alert guest-visit__alert--warning">
					<AlertCircle size={16} />
					<span>{cancelError}</span>
				</div>
			)}

			<div className="guest-visit__card">
				{loading && visits.length === 0 ? (
					<LoadingState size="sm" text="Đang tải danh sách lượt khách..." />
				) : listError ? (
					<ErrorState size="sm" message={listError} onRetry={() => loadVisits(page)} />
				) : visits.length === 0 ? (
					<EmptyState
						size="sm"
						icon={Inbox}
						title="Bạn chưa có lượt khách nào"
						description={cannotHost ? undefined : "Bấm “Tạo lượt khách” để đăng ký khách đến làm việc."}
					/>
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
								<tr
									key={v.id}
									data-visit-id={v.id}
									className={highlightVisitId === v.id ? "guest-visit__row--highlight" : undefined}
								>
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
									<td>
										{renderStatus(v.status)}
										{/* BR-GV-38: lượt đã duyệt còn khách chưa đăng ký khuôn mặt */}
										{getMissingPhotoInfo(v) && (
											<div className="guest-visit__photo-missing">
												<Badge variant="warning">
													Chưa có ảnh ({getMissingPhotoInfo(v).missing}/{getMissingPhotoInfo(v).total} khách)
												</Badge>
												<span className="guest-visit__photo-missing-hint">
													Mở Chi tiết để gắn ảnh cho khách trước giờ bắt đầu
												</span>
											</div>
										)}
									</td>
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
				initialFocus="field"
				onClose={() => !creating && setCreateOpen(false)}
				title="Tạo lượt khách"
				subtitle="Nhập họ tên, đơn vị và ảnh khuôn mặt của từng khách (khách phải đồng ý chụp ảnh). Hệ thống không nhận CCCD, số điện thoại, địa chỉ hay email."
				icon={UserPlus}
				size="lg"
				footer={
					<>
						{!creating && createBlockers.length > 0 && (
							<div id="guest-visit-create-hint" className="guest-visit__submit-hint" role="status">
								<AlertCircle size={14} className="guest-visit__submit-hint-icon" />
								<span>Cần {createBlockers.join("; ")} để gửi duyệt.</span>
							</div>
						)}
						<Button
							onClick={submitCreate}
							loading={creating}
							disabled={!canSubmitCreate}
							aria-describedby={createBlockers.length > 0 ? "guest-visit-create-hint" : undefined}
						>
							Gửi duyệt
						</Button>
					</>
				}
			>
				<fieldset className="guest-visit__form" disabled={creating}>
					{createError && (
						<div
							ref={createErrorRef}
							tabIndex={-1}
							role="alert"
							className="guest-visit__alert guest-visit__alert--danger"
						>
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
						<span className={`guest-visit__hint ${purposeState.error ? "guest-visit__hint--error" : ""}`}>
							{purposeState.counter}
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
						<span className="guest-visit__label">Khu vực * (chỉ loại Bảo mật)</span>
						{loadingAreas ? (
							<span className="guest-visit__muted">Đang tải khu vực…</span>
						) : selectableAreas.length === 0 ? (
							<span className="guest-visit__muted">Không có khu vực nào nhận khách.</span>
						) : (
							<div className="guest-visit__areas">
								{selectableAreas.map((a) => {
									const level = getLevelConfig(a.areaLevel);
									const blocked = a.hostCanInvite === false;
									const blockReason = blocked
										? AREA_BLOCK_REASONS[a.reasonCode] || "Không mời được khách vào khu này trong khung giờ đã chọn"
										: null;
									return (
										<label
											key={a.id}
											className={`guest-visit__area${blocked ? " guest-visit__area--blocked" : ""}`}
											title={blockReason || undefined}
										>
											<input
												type="checkbox"
												checked={!blocked && areaIds.includes(a.id)}
												disabled={blocked}
												onChange={() => toggleArea(a.id)}
											/>
											<span className="guest-visit__area-name">
												{a.name}
												{blockReason && <span className="guest-visit__area-reason">{blockReason}</span>}
											</span>
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
							<div
								className={`guest-visit__guest-row guest-visit__guest-row--card${errorGuestIndex === i ? " guest-visit__guest-row--error" : ""}`}
								key={g.uid}
								data-guest-index={i}
							>
								<input
									aria-label={`Họ tên khách ${i + 1}`}
									placeholder="Họ tên (2–100 ký tự)"
									value={g.fullName}
									maxLength={100}
									onChange={(e) => updateGuest(i, "fullName", e.target.value)}
								/>
								<input
									aria-label={`Đơn vị của khách ${i + 1}`}
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
									aria-label={`Xóa khách ${i + 1}`}
									title="Xóa khách"
								/>
								<GuestPhotoPicker
									id={`guest-photo-${g.uid}`}
									guestLabel={`khách ${i + 1}`}
									value={g.photo}
									onChange={(next) => {
										updateGuest(i, "photo", next);
										if (errorGuestIndex === i) setErrorGuestIndex(null);
									}}
									maxKb={createLimits.photoMaxKb}
									consentVersion={createLimits.consentVersion}
									disabled={creating}
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
				closeOnBackdrop
				icon={Eye}
				size="lg"
				footer={
					<>
						{detail && canHostCancel(detail) && (
							<Button variant="danger" icon={Ban} onClick={() => openCancel(detail)}>
								Huỷ lượt
							</Button>
						)}
						{/* Có nút hành động thì bỏ "Đóng" (còn X + Esc); chỉ để xem thì giữ "Đóng" */}
						{!(detail && canHostCancel(detail)) && (
							<Button variant="secondary" onClick={() => setDetail(null)}>
								Đóng
							</Button>
						)}
					</>
				}
			>
				{detail && (
					<div className="guest-visit__detail">
						<div className="guest-visit__detail-row">
							<span className="guest-visit__label">Trạng thái</span>
							{/* Bọc div như GuestVisitReviewPage: Badge làm ô grid trực tiếp sẽ bị kéo giãn hết cột 1fr */}
							<div>{renderStatus(detail.status)}</div>
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
												<GuestPhotoThumb key={`${g.id}-${g.photoAttachedAt || ""}`} visitId={detail.id} guest={g} />
												{canHostReplacePhoto(detail, g) && (
													<div className="guest-visit__photo-view">
														<Button variant="secondary" size="sm" icon={Camera} onClick={() => openReplace(detail, g)}>
															{g.biometricStatus === "PHOTO_READY" || g.biometricStatus === "PHOTO_ONLY" ? "Thay ảnh" : "Gắn ảnh"}
														</Button>
													</div>
												)}
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
						? new Date(cancelTarget.startTime) <= new Date()
							? "Lượt đã bắt đầu: huỷ sẽ thu hồi quyền của khách ngay. Ảnh và dữ liệu khuôn mặt đã gắn được giữ thêm theo thời hạn lưu trong cấu hình hệ thống, sau đó tự động xóa."
							: "Lượt đã được duyệt, chưa tới giờ bắt đầu: huỷ sẽ thu hồi quyền của khách và xóa ngay dữ liệu khuôn mặt đã gắn."
						: "Lượt đang chờ duyệt sẽ bị huỷ, ảnh và dữ liệu khuôn mặt của khách bị xóa ngay."
				}
				icon={Ban}
				iconVariant="danger"
				footer={
					<>
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
						if (cancelReasonError && (e.target.value.trim().length === 0 || reasonLimits.isValid(e.target.value))) {
							setCancelReasonError(null);
						}
					}}
					error={cancelReasonError}
					required={false}
					disabled={cancelling}
				/>
			</Modal>

			{/* A5: gắn / thay ảnh khách */}
			<Modal
				isOpen={!!replaceTarget}
				onClose={closeReplace}
				title={replaceTarget?.guest?.biometricStatus === "NO_PHOTO" ? "Gắn ảnh khách" : "Thay ảnh khách"}
				subtitle="Chỉ thay được trước giờ bắt đầu của lượt. Ảnh cũ và dữ liệu khuôn mặt cũ bị xóa sau khi lưu ảnh mới."
				icon={Camera}
				size="md"
				footer={
					<Button
						onClick={submitReplace}
						loading={replacing}
						disabled={replacing || !replacePhoto.file || !replacePhoto.consent || !replaceLimits.consentVersion}
					>
						Lưu ảnh
					</Button>
				}
			>
				{replaceTarget && (
					<div className="guest-visit__form">
						{replaceError && (
							<div className="guest-visit__alert guest-visit__alert--danger" role="alert">
								<AlertCircle size={16} />
								<span>{replaceError}</span>
							</div>
						)}
						<div>
							Khách: <strong>{replaceTarget.guest.fullName}</strong>
							{replaceTarget.guest.organization && <span> ({replaceTarget.guest.organization})</span>}
						</div>
						<GuestPhotoPicker
							id={`guest-replace-${replaceTarget.guest.id}`}
							guestLabel={replaceTarget.guest.fullName}
							value={replacePhoto}
							onChange={setReplacePhoto}
							maxKb={replaceLimits.photoMaxKb}
							consentVersion={replaceLimits.consentVersion}
							disabled={replacing}
						/>
					</div>
				)}
			</Modal>
		</div>
	);
}
