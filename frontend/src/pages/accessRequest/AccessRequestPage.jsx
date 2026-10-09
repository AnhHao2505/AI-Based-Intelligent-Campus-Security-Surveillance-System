import React, { useState, useEffect, useCallback, useRef } from "react";
import {
	KeyRound,
	Clock,
	User,
	Users,
	CheckCircle2,
	XCircle,
	AlertCircle,
	AlertTriangle,
	ShieldAlert,
	X,
	RefreshCw,
	Send,
	Inbox,
	ChevronLeft,
	ChevronRight,
	Ban,
} from "lucide-react";
import Modal from "../../components/ui/Modal";
import Button from "../../components/ui/Button";
import accessRequestService from "../../services/accessRequestService";
import { getLevelConfig, AREA_LEVEL_CONFIG } from "../../utils/areaHelpers";
import { useAuth } from "../../context/AuthContext";
import "../../styles/AccessRequestPage.css";
import PageHeader from "../../components/ui/PageHeader";
import { formatLocation } from "../../utils/formatLocation";
import { formatDateTime, formatRange } from "../../utils/formatDateTime";
import MemberCodeCombobox from "../../components/accessRequest/MemberCodeCombobox";

export default function AccessRequestPage() {
	const { user } = useAuth();

	// Available Areas
	const [areas, setAreas] = useState([]);
	const [loadingAreas, setLoadingAreas] = useState(false);

	// Form State
	const [selectedAreaId, setSelectedAreaId] = useState("");
	const [requestType, setRequestType] = useState("INDIVIDUAL"); // 'INDIVIDUAL' | 'GROUP'
	const [requestDate, setRequestDate] = useState("");
	// UI-15: ngày kết thúc riêng để đặt được đơn qua nửa đêm (mặc định = ngày bắt đầu)
	const [endDate, setEndDate] = useState("");
	const [startHour, setStartHour] = useState("08:00");
	const [endHour, setEndHour] = useState("11:00");
	const [purpose, setPurpose] = useState("");

	// Group Members state
	const [memberCodeInput, setMemberCodeInput] = useState("");
	const [memberList, setMemberList] = useState([]); // [{ userCode, fullName }]
	const [lookingUpMember, setLookingUpMember] = useState(false);

	// Submit & Alert state
	const [submitting, setSubmitting] = useState(false);
	const [formError, setFormError] = useState(null);
	// Lỗi tra cứu / thêm thành viên: hiện ngay dưới ô mã thành viên (không đẩy lên đầu form)
	const [memberError, setMemberError] = useState(null);
	const [formSuccess, setFormSuccess] = useState(null);

	// History State
	const [historyList, setHistoryList] = useState([]);
	const [historyStatusFilter, setHistoryStatusFilter] = useState("");
	const [historyAreaFilter, setHistoryAreaFilter] = useState("");
	const [loadingHistory, setLoadingHistory] = useState(false);
	const [historyPage, setHistoryPage] = useState(0);
	const [historyTotalPages, setHistoryTotalPages] = useState(1);
	const [historyTotalElements, setHistoryTotalElements] = useState(0);
	const [selectedDetail, setSelectedDetail] = useState(null);

	// Expandable Rejection Reason rows in table (Set of request IDs)
	const [expandedRejectIds, setExpandedRejectIds] = useState(new Set());

	// Cancel Request state
	const [cancelItem, setCancelItem] = useState(null);
	const [cancelling, setCancelling] = useState(false);
	const [cancelError, setCancelError] = useState(null);
	const [historyWarning, setHistoryWarning] = useState(null);

	// Ref to scroll down to history card upon successful submission
	const historyCardRef = useRef(null);

	// Helper: Default times (tomorrow 08:00 to 11:00)
	const initDefaultTimes = () => {
		const tomorrow = new Date();
		tomorrow.setDate(tomorrow.getDate() + 1);
		const yyyy = tomorrow.getFullYear();
		const mm = String(tomorrow.getMonth() + 1).padStart(2, "0");
		const dd = String(tomorrow.getDate()).padStart(2, "0");

		setRequestDate(`${yyyy}-${mm}-${dd}`);
		setEndDate(`${yyyy}-${mm}-${dd}`);
		setStartHour("08:00");
		setEndHour("11:00");
	};

	// Thời điểm bắt đầu / kết thúc theo giờ trình duyệt (ngày kết thúc mặc định = ngày bắt đầu)
	const getStartDateTime = () => new Date(`${requestDate}T${startHour}:00`);
	const getEndDateTime = () => new Date(`${endDate || requestDate}T${endHour}:00`);

	// Check if chosen time range is valid
	const isTimeValid = () => {
		if (!requestDate || !startHour || !endHour) return false;
		return getEndDateTime() > getStartDateTime();
	};

	// Đổi ngày bắt đầu: ngày kết thúc đi theo nếu đang trùng ngày cũ hoặc sớm hơn ngày mới
	const handleStartDateChange = (value) => {
		if (!endDate || endDate === requestDate || endDate < value) {
			setEndDate(value);
		}
		setRequestDate(value);
	};

	// Real-time summary text for date & time
	const getTimeSummary = () => {
		if (!requestDate || !startHour || !endHour) {
			return { isError: false, text: "" };
		}
		const startDateTime = getStartDateTime();
		const endDateTime = getEndDateTime();

		if (endDateTime <= startDateTime) {
			return {
				isError: true,
				text: "Thời điểm kết thúc phải sau thời điểm bắt đầu",
			};
		}

		// A-07: không tự kiểm "giờ bắt đầu ở quá khứ" với ngưỡng viết cứng — ngưỡng là
		// ACCESS_REQUEST_PAST_START_BUFFER_MINUTES (BE đọc cấu hình, NORMAL_USER không đọc được); BE trả lỗi khi gửi.

		const diffMin = Math.round((endDateTime - startDateTime) / 60000);
		const hours = Math.floor(diffMin / 60);
		const mins = diffMin % 60;
		let durationStr = "";
		if (hours > 0 && mins > 0) {
			durationStr = `${hours} tiếng ${mins} phút`;
		} else if (hours > 0) {
			durationStr = `${hours} tiếng`;
		} else {
			durationStr = `${mins} phút`;
		}

		const daysOfWeek = [
			"Chủ Nhật",
			"Thứ Hai",
			"Thứ Ba",
			"Thứ Tư",
			"Thứ Năm",
			"Thứ Sáu",
			"Thứ Bảy",
		];
		const describeDay = (dt) =>
			`${daysOfWeek[dt.getDay()] || ""}, ${String(dt.getDate()).padStart(2, "0")}/${String(dt.getMonth() + 1).padStart(2, "0")}/${dt.getFullYear()}`;
		const sameDay = startDateTime.toDateString() === endDateTime.toDateString();

		return {
			isError: false,
			text: sameDay
				? `${describeDay(startDateTime)} · ${startHour} – ${endHour} (${durationStr})`
				: `${describeDay(startDateTime)} ${startHour} → ${describeDay(endDateTime)} ${endHour} (${durationStr})`,
		};
	};

	// Load available areas
	const loadAreas = useCallback(async () => {
		setLoadingAreas(true);
		try {
			const data = await accessRequestService.getAvailableAreas();
			const list = Array.isArray(data) ? data : data?.content || [];
			setAreas(list);
		} catch (err) {
			console.error("Lỗi khi tải danh sách khu vực:", err);
		} finally {
			setLoadingAreas(false);
		}
	}, []);

	// Load my requests
	const loadMyRequests = useCallback(
		async (page = 0, status = historyStatusFilter, areaId = historyAreaFilter) => {
			setLoadingHistory(true);
			try {
				const res = await accessRequestService.getMyRequests({
					status: status || undefined,
					areaId: areaId || undefined,
					page,
					size: 10,
				});
				setHistoryList(res?.content || []);
				setHistoryTotalPages(res?.totalPages || 1);
				setHistoryTotalElements(res?.totalElements || 0);
				setHistoryPage(page);
			} catch (err) {
				console.error("Lỗi khi tải lịch sử yêu cầu:", err);
			} finally {
				setLoadingHistory(false);
			}
		},
		[historyStatusFilter, historyAreaFilter],
	);

	useEffect(() => {
		loadAreas();
		initDefaultTimes();
	}, [loadAreas]);

	useEffect(() => {
		loadMyRequests(0, historyStatusFilter, historyAreaFilter);
	}, [historyStatusFilter, historyAreaFilter, loadMyRequests]);

	// Selected area object
	const areaList = Array.isArray(areas) ? areas : areas?.content || [];
	const currentArea = areaList.find((a) => a.id === selectedAreaId);

	// A-06 (BR-RQ-28): khu vực có nhận đơn nhóm hay không do BE quyết (cờ groupRequestAllowed của available-areas,
	// theo ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE). Thiếu cờ thì giữ luật mặc định: Tuyệt mật chỉ nhận đơn cá nhân.
	const isGroupBlocked = (area) => {
		if (!area) return false;
		if (typeof area.groupRequestAllowed === "boolean") return !area.groupRequestAllowed;
		return area.areaLevel === "HIGHLY_CONFIDENTIAL" || area.areaLevel === "PRIVATE";
	};
	const groupBlocked = isGroupBlocked(currentArea);

	// When area changes, if area does not accept group requests, force INDIVIDUAL
	const handleAreaChange = (e) => {
		const areaId = e.target.value;
		setSelectedAreaId(areaId);
		const found = areaList.find((a) => a.id === areaId);
		if (isGroupBlocked(found)) {
			setRequestType("INDIVIDUAL");
			setMemberList([]);
		}
	};

	// Member lookup
	// codeOverride: mã chọn từ gợi ý (autocomplete) — vẫn đi qua resolve-members như khi gõ tay
	const handleAddMember = async (codeOverride) => {
		const rawInput = (typeof codeOverride === "string" ? codeOverride : memberCodeInput).trim();
		if (!rawInput) return;

		// Tách danh sách mã bằng dấu phẩy, chấm phẩy hoặc khoảng trắng
		const candidateCodes = rawInput
			.split(/[\s,;]+/)
			.map((c) => c.trim())
			.filter(Boolean);

		if (candidateCodes.length === 0) return;

		// Lọc các mã đã có trong danh sách
		const existingCodeSet = new Set(
			memberList.map((m) => m.userCode.toLowerCase()),
		);
		const newCodes = [];
		const duplicateCodes = [];

		for (const code of candidateCodes) {
			if (existingCodeSet.has(code.toLowerCase())) {
				duplicateCodes.push(code);
			} else {
				newCodes.push(code);
			}
		}

		if (newCodes.length === 0) {
			setMemberError(
				`Mã người dùng ${duplicateCodes.join(", ")} đã có trong danh sách.`,
			);
			return;
		}

		setLookingUpMember(true);
		setMemberError(null);
		try {
			const results = await accessRequestService.resolveMembers(newCodes);
			const validMembers = [];
			const invalidMessages = [];

			if (Array.isArray(results)) {
				for (const res of results) {
					if (res.found) {
						validMembers.push({
							userCode: res.userCode,
							fullName: res.fullName || "Người dùng",
						});
					} else {
						invalidMessages.push(
							`${res.userCode}: ${res.reason || "Không tìm thấy người dùng hợp lệ"}`,
						);
					}
				}
			}

			if (validMembers.length > 0) {
				setMemberList((prev) => [...prev, ...validMembers]);
				setMemberCodeInput("");
			}

			if (invalidMessages.length > 0) {
				setMemberError(invalidMessages.join(" | "));
			}
		} catch (err) {
			if (err.status === 429) {
				setMemberError("Bạn tra cứu quá nhanh, vui lòng thử lại sau ít phút");
			} else {
				setMemberError(
					err.message || "Không thể tra cứu danh sách mã người dùng",
				);
			}
		} finally {
			setLookingUpMember(false);
		}
	};

	const handleRemoveMember = (code) => {
		setMemberList((prev) => prev.filter((m) => m.userCode !== code));
	};

	// Toggle inline rejection reason in table
	const toggleRejectReason = (id) => {
		setExpandedRejectIds((prev) => {
			const next = new Set(prev);
			if (next.has(id)) {
				next.delete(id);
			} else {
				next.add(id);
			}
			return next;
		});
	};

	// Submit Request
	const handleSubmit = async (e) => {
		e.preventDefault();
		setFormError(null);
		setFormSuccess(null);

		if (!selectedAreaId) {
			setFormError("Vui lòng chọn khu vực cần đăng ký");
			return;
		}

		if (!requestDate || !startHour || !endHour) {
			setFormError("Vui lòng chọn đầy đủ ngày và khung thời gian");
			return;
		}

		if (!isTimeValid()) {
			setFormError("Thời điểm kết thúc phải sau thời điểm bắt đầu");
			return;
		}

		// A-07: kiểm "giờ bắt đầu ở quá khứ" để BE làm (ngưỡng theo cấu hình), lỗi hiện qua setFormError ở catch
		const start = getStartDateTime();
		const end = getEndDateTime();

		let cleanMemberCodes = [];
		if (requestType === "GROUP") {
			if (groupBlocked) {
				setFormError(
					`Khu vực ${AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.name} chỉ cho phép đăng ký cá nhân.`,
				);
				return;
			}

			if (memberList.length === 0) {
				setFormError(
					"Yêu cầu theo nhóm bắt buộc phải thêm ít nhất một mã số thành viên",
				);
				return;
			}

			// Deduplicate and silently exclude requester
			const currentUser = JSON.parse(localStorage.getItem("user") || "{}");
			const myCode = (currentUser.userCode || "").trim().toLowerCase();
			const seen = new Set();
			for (const m of memberList) {
				const code = (m.userCode || "").trim();
				const lower = code.toLowerCase();
				if (lower && lower !== myCode && !seen.has(lower)) {
					seen.add(lower);
					cleanMemberCodes.push(code);
				}
			}

			if (cleanMemberCodes.length === 0) {
				setFormError(
					"Yêu cầu theo nhóm bắt buộc phải có ít nhất một thành viên khác ngoài người tạo.",
				);
				return;
			}
		}

		if (!purpose.trim()) {
			setFormError("Vui lòng nhập mục đích sử dụng khu vực");
			return;
		}

		setSubmitting(true);
		try {
			if (requestType === "GROUP") {
				await accessRequestService.createGroupRequest({
					areaId: selectedAreaId,
					startTime: start.toISOString(),
					endTime: end.toISOString(),
					purpose: purpose.trim(),
					memberUserCodes: cleanMemberCodes,
				});
			} else {
				await accessRequestService.createIndividualRequest({
					areaId: selectedAreaId,
					startTime: start.toISOString(),
					endTime: end.toISOString(),
					purpose: purpose.trim(),
				});
			}

			setFormSuccess(
				"Gửi yêu cầu truy cập thành công! Ban quản lý sẽ sớm xem xét phê duyệt.",
			);

			// Reset form
			setSelectedAreaId("");
			setPurpose("");
			setMemberList([]);
			initDefaultTimes();

			// Refresh history list and smooth scroll down
			loadMyRequests(0, historyStatusFilter, historyAreaFilter);
			setTimeout(() => {
				historyCardRef.current?.scrollIntoView({ behavior: "smooth" });
			}, 500);
		} catch (err) {
			setFormError(err.message || "Đã có lỗi xảy ra khi tạo yêu cầu.");
		} finally {
			setSubmitting(false);
		}
	};

	// Confirm cancel request
	const handleConfirmCancel = async () => {
		if (!cancelItem) return;
		setCancelling(true);
		setCancelError(null);
		try {
			await accessRequestService.cancelRequest(cancelItem.id);
			setCancelItem(null);
			setFormSuccess("Hủy yêu cầu truy cập thành công!");
			loadMyRequests(historyPage, historyStatusFilter, historyAreaFilter);
			setTimeout(() => setFormSuccess(null), 4000);
		} catch (err) {
			if (err.status === 409) {
				setCancelItem(null);
				setHistoryWarning(
					err.message || "Yêu cầu này vừa được xử lý, không thể hủy.",
				);
				loadMyRequests(historyPage, historyStatusFilter, historyAreaFilter);
				setTimeout(() => setHistoryWarning(null), 7000);
			} else {
				setCancelError(err.message || "Không thể hủy yêu cầu truy cập.");
			}
		} finally {
			setCancelling(false);
		}
	};

	const timeSummary = getTimeSummary();
	// UX-03: lý do nút "Gửi yêu cầu" đang khoá — cùng điều kiện với isFormValid
	const submitBlockers = [];
	if (!selectedAreaId) submitBlockers.push("chọn khu vực cần truy cập");
	if (!requestDate || !startHour || !endHour) {
		submitBlockers.push("chọn ngày và khung giờ");
	} else if (!isTimeValid()) {
		submitBlockers.push("thời điểm kết thúc phải sau thời điểm bắt đầu");
	}
	if (requestType === "GROUP" && memberList.length === 0) {
		submitBlockers.push("thêm ít nhất một thành viên nhóm");
	}
	if (!purpose.trim()) submitBlockers.push("nhập mục đích sử dụng khu vực");
	const isFormValid = submitBlockers.length === 0;

	return (
		<div className="arp-container">
			<PageHeader
				title="Yêu cầu truy cập"
				description="Gửi yêu cầu ra vào khu vực cần cấp phép và theo dõi trạng thái các yêu cầu của bạn."
			/>
			{/* THẺ 1: YÊU CẦU TRUY CẬP MỚI */}
			<div className="arp-card">
				{/* 2a. Đầu thẻ */}
				<div className="arp-card__header">
					<div className="arp-card__header-left">
						<div className="arp-card__icon-box">
							<KeyRound size={16} />
						</div>
						<div>
							<h2 className="arp-card__title">Yêu cầu truy cập mới</h2>
							<p className="arp-card__subtitle">
								Yêu cầu sẽ được Ban quản lý xem xét trước khi phê duyệt
							</p>
						</div>
					</div>
				</div>

				{/* 2d. Banner thành công / lỗi */}
				{formSuccess && (
					<div
						className="arp-banner arp-banner--success"
						style={{ margin: "14px 20px 0 20px" }}
					>
						<CheckCircle2 size={16} />
						<span>{formSuccess}</span>
					</div>
				)}

				{formError && (
					<div
						className="arp-banner arp-banner--error"
						style={{ margin: "14px 20px 0 20px" }}
					>
						<AlertCircle size={16} />
						<span>{formError}</span>
					</div>
				)}

				{/* 2b. Thân thẻ */}
				<form
					onSubmit={handleSubmit}
					className="arp-card__body"
				>
					{/* TRƯỜNG 1: Khu vực cần truy cập */}
					<div className="arp-form-group">
						<label className="arp-label">
							<span>Khu vực cần truy cập</span>
							<span className="arp-required">*</span>
						</label>
						<select
							className="arp-select"
							value={selectedAreaId}
							onChange={handleAreaChange}
							disabled={loadingAreas || submitting}
							required
						>
							<option value="">-- Chọn khu vực cần đăng ký truy cập --</option>
							{areas.map((a) => {
								const loc = formatLocation(a.building, a.floor);
								return (
									<option
										key={a.id}
										value={a.id}
									>
										{loc ? `${a.name} (${loc})` : a.name}
									</option>
								);
							})}
						</select>

						{currentArea &&
							(() => {
								const lvlConf = getLevelConfig(currentArea.areaLevel);
								const isHighlyConf =
									currentArea.areaLevel === "HIGHLY_CONFIDENTIAL" ||
									currentArea.areaLevel === "PRIVATE";
								return (
									<div
										className={`arp-area-info ${
											isHighlyConf
												? "arp-area-info--private"
												: "arp-area-info--semi"
										}`}
									>
										{isHighlyConf ? (
											<ShieldAlert
												size={16}
												style={{ flexShrink: 0 }}
											/>
										) : (
											<AlertTriangle
												size={16}
												style={{ flexShrink: 0 }}
											/>
										)}
										<div>
											<div style={{ fontWeight: 600, marginBottom: "2px" }}>
												Cấp độ an ninh:{" "}
												<span className="arp-area-level-name">
													{lvlConf.name}
												</span>
											</div>
											<div>
												{isHighlyConf
													? groupBlocked
														? `Khu vực ${AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.name}. Chỉ áp dụng hình thức đăng ký truy cập Cá nhân (không hỗ trợ đăng ký theo nhóm).`
														: `Khu vực ${AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.name}. Đơn nhóm được phép theo cấu hình hệ thống; thành viên cần đáp ứng điều kiện cấp độ truy cập của khu vực.`
													: currentArea?.areaLevel === "CONFIDENTIAL_CONTACT_REQUIRED"
													? "Khu vực yêu cầu liên hệ trước. Người tạo đơn đủ cấp độ truy cập có thể bảo lãnh cho các thành viên trong nhóm tham gia cùng thời gian đăng ký."
													: "Khu vực yêu cầu phê duyệt trước khi vào."}
											</div>
										</div>
									</div>
								);
							})()}
					</div>

					{/* TRƯỜNG 2: Hình thức đăng ký */}
					<div className="arp-form-group">
						<label className="arp-label">
							<span>Hình thức đăng ký</span>
							<span className="arp-required">*</span>
						</label>
						<div className="arp-type-grid">
							<button
								type="button"
								className={`arp-type-btn ${requestType === "INDIVIDUAL" ? "arp-type-btn--selected" : ""}`}
								onClick={() => setRequestType("INDIVIDUAL")}
								disabled={submitting}
							>
								<User
									size={18}
									className="arp-type-btn__icon"
								/>
								<div className="arp-type-btn__name">Cá nhân</div>
								<div className="arp-type-btn__desc">
									Đăng ký quyền ra vào khu vực cho chính bạn.
								</div>
							</button>

							<button
								type="button"
								className={`arp-type-btn ${requestType === "GROUP" ? "arp-type-btn--selected" : ""}`}
								onClick={() => {
									if (!groupBlocked) {
										setRequestType("GROUP");
									}
								}}
								disabled={groupBlocked || submitting}
								title={
									groupBlocked
										? `Khu vực ${AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.name} chỉ cho phép đăng ký cá nhân`
										: ""
								}
							>
								<Users
									size={18}
									className="arp-type-btn__icon"
								/>
								<div className="arp-type-btn__name">Nhóm nội bộ</div>
								<div className="arp-type-btn__desc">
									Đăng ký quyền ra vào cho một nhóm thành viên theo danh sách mã
									số.
								</div>
							</button>
						</div>
					</div>

					{/* TRƯỜNG 3: Khung thời gian */}
					<div className="arp-form-group">
						<label className="arp-label">
							<span>Khung thời gian truy cập</span>
							<span className="arp-required">*</span>
						</label>
						<div className="arp-time-grid arp-time-grid--range">
							<div>
								<label className="arp-sub-label">Từ ngày</label>
								<input
									type="date"
									className="arp-input"
									value={requestDate}
									onChange={(e) => handleStartDateChange(e.target.value)}
									disabled={submitting}
									required
								/>
							</div>
							<div>
								<label className="arp-sub-label">Từ giờ</label>
								<input
									type="time"
									className="arp-input"
									value={startHour}
									onChange={(e) => setStartHour(e.target.value)}
									disabled={submitting}
									required
								/>
							</div>
							<div>
								<label className="arp-sub-label">Đến ngày</label>
								<input
									type="date"
									className="arp-input"
									value={endDate || requestDate}
									min={requestDate || undefined}
									onChange={(e) => setEndDate(e.target.value)}
									disabled={submitting}
									required
								/>
							</div>
							<div>
								<label className="arp-sub-label">Đến giờ</label>
								<input
									type="time"
									className="arp-input"
									value={endHour}
									onChange={(e) => setEndHour(e.target.value)}
									disabled={submitting}
									required
								/>
							</div>
						</div>
						{timeSummary?.text && (
							<div
								style={{
									fontSize: "12px",
									marginTop: "6px",
									color: timeSummary.isError
										? "var(--theme-danger, #ef4444)"
										: "var(--theme-text-secondary, #94a3b8)",
								}}
							>
								{timeSummary.text}
							</div>
						)}
					</div>

					{/* TRƯỜNG 4: Danh sách thành viên (nếu chọn GROUP) */}
					{requestType === "GROUP" && (
						<div className="arp-form-group">
							<label className="arp-label">
								<span>Danh sách mã số thành viên nhóm</span>
								<span className="arp-required">*</span>
							</label>
							<div className="arp-member-lookup">
								<MemberCodeCombobox
									placeholder="Nhập mã số thành viên (vd: SV-002, GV-001...) hoặc bấm ↓"
									value={memberCodeInput}
									onChange={(text) => {
										setMemberCodeInput(text);
										if (memberError) setMemberError(null);
									}}
									onPick={(code) => handleAddMember(code)}
									onSubmitTyped={() => handleAddMember()}
									excludeCodes={[...memberList.map((m) => m.userCode), user?.userCode]}
									disabled={lookingUpMember || submitting}
									describedBy={memberError ? "arp-member-error" : undefined}
								/>
								<button
									type="button"
									className="arp-btn arp-btn--secondary"
									onClick={() => handleAddMember()}
									disabled={
										!memberCodeInput.trim() || lookingUpMember || submitting
									}
								>
									{lookingUpMember ? "Đang tra..." : "Thêm"}
								</button>
							</div>
							{memberError && (
								<div id="arp-member-error" className="arp-field-error" role="alert">
									{memberError}
								</div>
							)}

							{memberList.length > 0 && (
								<div className="arp-member-list">
									{memberList.map((m) => (
										<span
											key={m.userCode}
											className="arp-member-chip"
										>
											<span>
												{m.userCode} · {m.fullName}
											</span>
											<button
												type="button"
												className="arp-member-chip__remove"
												onClick={() => handleRemoveMember(m.userCode)}
												title="Xóa thành viên"
											>
												<X size={13} />
											</button>
										</span>
									))}
								</div>
							)}

							<div className="arp-hint">
								{memberList.length > 0
									? `Đã thêm ${memberList.length} thành viên`
									: "Bấm Enter hoặc nút Thêm để xác thực mã số thành viên"}
							</div>
						</div>
					)}

					{/* TRƯỜNG 5: Mục đích sử dụng */}
					<div className="arp-form-group">
						<label className="arp-label">
							<span>Mục đích sử dụng khu vực</span>
							<span className="arp-required">*</span>
						</label>
						<textarea
							className="arp-textarea"
							rows={3}
							placeholder="Mô tả cụ thể mục đích truy cập (ví dụ: Họp nhóm đồ án Capstone, nghiên cứu phòng Lab Robotics, chuẩn bị sự kiện...)"
							value={purpose}
							onChange={(e) => setPurpose(e.target.value)}
							maxLength={1000}
							disabled={submitting}
							required
						/>
						<div className="arp-purpose-meta">
							<span className="arp-hint">Bắt buộc, tối đa 1000 ký tự</span>
							<span
								className="arp-hint"
								style={{
									color:
										purpose.length > 1000
											? "var(--theme-danger)"
											: "var(--theme-text-muted)",
								}}
							>
								{purpose.length}/1000 ký tự
							</span>
						</div>
					</div>

					{/* 2c. Chân thẻ */}
					<div className="arp-card__footer">
						{!isFormValid && !submitting && (
							<div id="arp-submit-hint" className="arp-submit-hint" role="status">
								<AlertCircle size={14} className="arp-submit-hint__icon" />
								<span>Cần {submitBlockers.join("; ")} để gửi yêu cầu.</span>
							</div>
						)}
						<button
							type="submit"
							className="arp-btn-submit"
							disabled={!isFormValid || submitting}
							aria-describedby={!isFormValid ? "arp-submit-hint" : undefined}
						>
							{submitting ? (
								<>
									<RefreshCw
										size={15}
										className="arp-spin"
									/>
									<span>Đang gửi...</span>
								</>
							) : (
								<>
									<Send size={15} />
									<span>Gửi yêu cầu</span>
								</>
							)}
						</button>
					</div>
				</form>
			</div>

			{/* THẺ 2: YÊU CẦU CỦA TÔI */}
			<div
				className="arp-card"
				ref={historyCardRef}
			>
				{/* 3a. Đầu thẻ */}
				<div className="arp-card__header">
					<div className="arp-card__header-left">
						<h2 className="arp-card__title">Yêu cầu của tôi</h2>
					</div>

					<div className="arp-filter-group">
						{[
							{ label: "Tất cả", val: "" },
							{ label: "Chờ duyệt", val: "PENDING" },
							{ label: "Đã duyệt", val: "APPROVED" },
							{ label: "Hoàn thành", val: "FINISHED" },
							{ label: "Bị từ chối", val: "REJECTED" },
							{ label: "Đã hủy", val: "CANCELLED" },
							{ label: "Hết hạn", val: "EXPIRED" },
						].map((f) => (
							<button
								key={f.val}
								type="button"
								className={`arp-filter-btn ${historyStatusFilter === f.val ? "arp-filter-btn--active" : ""}`}
								onClick={() => {
									setHistoryStatusFilter(f.val);
									setHistoryPage(0);
								}}
							>
								{f.label}
							</button>
						))}

						<select
							className="arp-select-filter"
							value={historyAreaFilter}
							onChange={(e) => {
								setHistoryAreaFilter(e.target.value);
								setHistoryPage(0);
							}}
						>
							<option value="">Tất cả khu vực</option>
							{areaList.map((a) => {
								const loc = formatLocation(a.building, a.floor);
								return (
									<option key={a.id} value={a.id}>
										{loc ? `${a.name} (${loc})` : a.name}
									</option>
								);
							})}
						</select>

						<button
							type="button"
							className="arp-refresh-btn"
							onClick={() => loadMyRequests(historyPage, historyStatusFilter, historyAreaFilter)}
							title="Làm mới danh sách"
							disabled={loadingHistory}
						>
							<RefreshCw
								size={13}
								className={loadingHistory ? "arp-spin" : ""}
							/>
						</button>
					</div>
				</div>

				{historyWarning && (
					<div
						className="arp-banner arp-banner--warning"
						style={{ margin: "14px 20px 0 20px" }}
					>
						<AlertTriangle size={16} />
						<span>{historyWarning}</span>
					</div>
				)}

				{/* 3b. Bảng & 3d. Bảng rỗng */}
				<div className="arp-card__table-wrapper">
					{loadingHistory ? (
						<div className="arp-empty">
							<RefreshCw
								size={24}
								className="arp-spin"
								style={{ marginBottom: "8px" }}
							/>
							<div className="arp-empty__text">
								Đang tải danh sách yêu cầu...
							</div>
						</div>
					) : historyList.length === 0 ? (
						<div className="arp-empty">
							<Inbox
								size={28}
								className="arp-empty__icon"
							/>
							<div className="arp-empty__text">Chưa có yêu cầu nào</div>
						</div>
					) : (
						<div className="arp-table-container">
							<table className="arp-table">
								<thead>
									<tr>
										<th>Khu vực</th>
										<th>Hình thức</th>
										<th className="ui-col-time-range">Thời gian truy cập</th>
										<th>Trạng thái</th>
										<th className="ui-col-time">Ngày tạo</th>
										<th style={{ textAlign: "right" }}>Thao tác</th>
									</tr>
								</thead>
								<tbody>
									{historyList.map((req) => (
										<React.Fragment key={req.id}>
											<tr>
												<td>
													<div style={{ fontWeight: 600 }}>{req.areaName}</div>
													{formatLocation(req.building, req.floor) && (
														<div className="arp-table-room-code">
															{formatLocation(req.building, req.floor)}
														</div>
													)}
												</td>
												<td>
													<span
														className={`arp-badge ${
															req.requestType === "GROUP"
																? "arp-badge--group"
																: "arp-badge--individual"
														}`}
													>
														{req.requestType === "GROUP" ? "Nhóm" : "Cá nhân"}
													</span>
													{req.isRequester === false && (
														<div
															style={{
																marginTop: "4px",
																fontSize: "11px",
																display: "flex",
																alignItems: "center",
																flexWrap: "wrap",
																gap: "4px",
															}}
														>
															<span
																style={{
																	display: "inline-block",
																	padding: "1px 6px",
																	borderRadius: "4px",
																	background: "rgba(59, 130, 246, 0.12)",
																	border: "1px solid rgba(59, 130, 246, 0.25)",
																	color: "var(--brand-blue, #3b82f6)",
																	fontWeight: 600,
																}}
															>
																Thành viên nhóm
															</span>
															{req.requesterName && (
																<span
																	style={{
																		color: "var(--theme-text-muted, #94a3b8)",
																	}}
																	title={`Người tạo đơn: ${req.requesterName}`}
																>
																	({req.requesterName})
																</span>
															)}
														</div>
													)}
												</td>
												<td>
													<div style={{ fontSize: "13px" }}>
														{formatRange(req.startTime, req.endTime)}
													</div>
												</td>
												<td>
													<span
														className={`arp-status-badge arp-status-badge--${req.status.toLowerCase()}`}
													>
														{req.status === "PENDING" && (
															<>
																<Clock size={11} />
																<span>Chờ duyệt</span>
															</>
														)}
														{req.status === "APPROVED" && (
															<>
																<CheckCircle2 size={11} />
																<span>Đã duyệt</span>
															</>
														)}
														{req.status === "FINISHED" && (
															<>
																<CheckCircle2 size={11} />
																<span>Hoàn thành</span>
															</>
														)}
														{req.status === "REJECTED" && (
															<>
																<XCircle size={11} />
																<span>Từ chối</span>
															</>
														)}
														{req.status === "CANCELLED" && (
															<>
																<Ban size={11} />
																<span>Đã hủy</span>
															</>
														)}
														{req.status === "EXPIRED" && (
															<>
																<AlertTriangle size={11} />
																<span>Hết hạn</span>
															</>
														)}
													</span>
													{req.status === "CANCELLED" && req.cancelSource === "SYSTEM" && (
														<div className="arp-cancel-system" title={req.cancelReason || ""}>
															Hủy bởi hệ thống: {req.cancelReason}
														</div>
													)}
												</td>
												<td
													style={{
														fontSize: "12px",
														color: "var(--theme-text-muted)",
													}}
												>
													{formatDateTime(req.createdAt)}
												</td>
												<td
													style={{ textAlign: "right", whiteSpace: "nowrap" }}
												>
													<div
														style={{
															display: "inline-flex",
															alignItems: "center",
															gap: "8px",
														}}
													>
														{req.status === "PENDING" && req.isRequester !== false && (
															<button
																type="button"
																className="arp-btn arp-btn--danger-ghost arp-btn--sm"
																onClick={() => {
																	setCancelItem(req);
																	setCancelError(null);
																}}
																title="Hủy yêu cầu truy cập này"
															>
																Hủy
															</button>
														)}
														{req.status === "REJECTED" && (
															<button
																type="button"
																className="arp-link-btn"
																onClick={() => toggleRejectReason(req.id)}
															>
																{expandedRejectIds.has(req.id)
																	? "Ẩn lý do"
																	: "Xem lý do"}
															</button>
														)}
														<button
															type="button"
															className="arp-btn arp-btn--secondary arp-btn--sm"
															onClick={() => setSelectedDetail(req)}
														>
															Chi tiết
														</button>
													</div>
												</td>
											</tr>

											{/* 3c. Hàng mở rộng lý do từ chối */}
											{req.status === "REJECTED" &&
												expandedRejectIds.has(req.id) && (
													<tr className="arp-reject-expand-row">
														<td colSpan={6}>
															<div className="arp-reject-expand-box">
																<div className="arp-reject-expand-label">
																	GHI CHÚ TỪ BAN QUẢN LÝ
																</div>
																<div className="arp-reject-expand-content">
																	{req.rejectionReason ||
																		"Không có lý do cụ thể được cung cấp."}
																</div>
															</div>
														</td>
													</tr>
												)}
										</React.Fragment>
									))}
								</tbody>
							</table>
						</div>
					)}
				</div>

				{/* 3e. Phân trang */}
				{historyTotalPages > 1 && (
					<div className="arp-pagination">
						<div className="arp-pagination__info">
							Hiển thị {historyTotalElements === 0 ? 0 : historyPage * 10 + 1}–
							{Math.min((historyPage + 1) * 10, historyTotalElements)} trên tổng
							số {historyTotalElements} yêu cầu
						</div>
						<div className="arp-pagination__controls">
							<button
								type="button"
								className="arp-page-btn"
								onClick={() =>
									loadMyRequests(historyPage - 1, historyStatusFilter, historyAreaFilter)
								}
								disabled={historyPage === 0 || loadingHistory}
								title="Trang trước"
							>
								<ChevronLeft size={14} />
							</button>

							{Array.from({ length: historyTotalPages }, (_, i) => i).map(
								(p) => {
									if (
										historyTotalPages <= 7 ||
										p === 0 ||
										p === historyTotalPages - 1 ||
										Math.abs(p - historyPage) <= 1
									) {
										return (
											<button
												key={p}
												type="button"
												className={`arp-page-btn ${p === historyPage ? "arp-page-btn--active" : ""}`}
												onClick={() => loadMyRequests(p, historyStatusFilter, historyAreaFilter)}
												disabled={loadingHistory}
											>
												{p + 1}
											</button>
										);
									} else if (p === 1 || p === historyTotalPages - 2) {
										return (
											<span
												key={p}
												className="arp-page-ellipsis"
											>
												...
											</span>
										);
									}
									return null;
								},
							)}

							<button
								type="button"
								className="arp-page-btn"
								onClick={() =>
									loadMyRequests(historyPage + 1, historyStatusFilter, historyAreaFilter)
								}
								disabled={
									historyPage >= historyTotalPages - 1 || loadingHistory
								}
								title="Trang sau"
							>
								<ChevronRight size={14} />
							</button>
						</div>
					</div>
				)}
			</div>

			{/* 4. MODAL CHI TIẾT */}
			{selectedDetail && (
				<div
					className="arp-modal-overlay"
					onClick={() => setSelectedDetail(null)}
				>
					<div
						className="arp-modal"
						onClick={(e) => e.stopPropagation()}
					>
						<div className="arp-modal__header">
							<div className="arp-modal__header-title">
								<div className="arp-modal__icon-box">
									<KeyRound size={16} />
								</div>
								<div>
									<h2 className="arp-modal__title">
										Chi tiết yêu cầu truy cập
									</h2>
								</div>
							</div>
							<button
								type="button"
								className="arp-modal__close"
								onClick={() => setSelectedDetail(null)}
							>
								<X size={16} />
							</button>
						</div>

						<div className="arp-modal__body">
							<div className="arp-detail-grid">
								<div className="arp-detail-item">
									<span className="arp-detail-label">Khu vực</span>
									<span className="arp-detail-val">
										{selectedDetail.areaName}
										{formatLocation(selectedDetail.building, selectedDetail.floor) &&
											` (${formatLocation(selectedDetail.building, selectedDetail.floor)})`}
									</span>
									<span
										style={{
											fontSize: "11px",
											color: "var(--theme-text-muted)",
										}}
									>
										Loại khu vực: {getLevelConfig(selectedDetail.areaLevel).name}
									</span>
								</div>

								<div className="arp-detail-item">
									<span className="arp-detail-label">Trạng thái</span>
									<div>
										<span
											className={`arp-status-badge arp-status-badge--${selectedDetail.status.toLowerCase()}`}
										>
											{selectedDetail.status === "PENDING" && (
												<>
													<Clock size={11} />
													<span>Chờ phê duyệt</span>
												</>
											)}
											{selectedDetail.status === "APPROVED" && (
												<>
													<CheckCircle2 size={11} />
													<span>Đã phê duyệt</span>
												</>
											)}
											{selectedDetail.status === "REJECTED" && (
												<>
													<XCircle size={11} />
													<span>Bị từ chối</span>
												</>
											)}
											{selectedDetail.status === "CANCELLED" && (
												<>
													<Ban size={11} />
													<span>Đã hủy</span>
												</>
											)}
											{selectedDetail.status === "EXPIRED" && (
												<>
													<AlertTriangle size={11} />
													<span>Hết hạn</span>
												</>
											)}
										</span>
										{selectedDetail.status === "CANCELLED" && selectedDetail.cancelSource === "SYSTEM" && (
											<div className="arp-cancel-system arp-cancel-system--detail">
												Hủy bởi hệ thống: {selectedDetail.cancelReason}
											</div>
										)}
									</div>
								</div>

								<div className="arp-detail-item">
									<span className="arp-detail-label">Thời gian bắt đầu</span>
									<span className="arp-detail-val">
										{formatDateTime(selectedDetail.startTime)}
									</span>
								</div>

								<div className="arp-detail-item">
									<span className="arp-detail-label">Thời gian kết thúc</span>
									<span className="arp-detail-val">
										{formatDateTime(selectedDetail.endTime)}
									</span>
								</div>

								<div className="arp-detail-item">
									<span className="arp-detail-label">Hình thức</span>
									<span className="arp-detail-val">
										{selectedDetail.requestType === "GROUP"
											? "Tập thể / Nhóm"
											: "Cá nhân"}
										{selectedDetail.isRequester === false && " "}
										{selectedDetail.isRequester === false && (
											<span
												style={{
													marginLeft: "8px",
													display: "inline-block",
													padding: "1px 6px",
													borderRadius: "4px",
													background: "rgba(59, 130, 246, 0.12)",
													border: "1px solid rgba(59, 130, 246, 0.25)",
													color: "var(--brand-blue, #3b82f6)",
													fontSize: "11px",
													fontWeight: 600,
												}}
											>
												Thành viên nhóm
											</span>
										)}
									</span>
									{selectedDetail.isRequester === false && selectedDetail.requesterName && (
										<span style={{ fontSize: "11px", color: "var(--theme-text-muted)" }}>
											Người tạo đơn: {selectedDetail.requesterName} ({selectedDetail.requesterCode || selectedDetail.requesterEmail})
										</span>
									)}
								</div>

								<div className="arp-detail-item">
									<span className="arp-detail-label">Ngày gửi yêu cầu</span>
									<span className="arp-detail-val">
										{formatDateTime(selectedDetail.createdAt)}
									</span>
								</div>
							</div>

							{/* Mục đích */}
							<div className="arp-detail-item">
								<span className="arp-detail-label">Mục đích sử dụng</span>
								<div className="arp-detail-box">{selectedDetail.purpose}</div>
							</div>

							{/* Thành viên (nếu nhóm) */}
							{selectedDetail.requestType === "GROUP" &&
								selectedDetail.members &&
								selectedDetail.members.length > 0 && (
									<div className="arp-detail-item">
										<span className="arp-detail-label">
											Danh sách thành viên nhóm ({selectedDetail.members.length}
											)
										</span>
										<div className="arp-member-list">
											{selectedDetail.members.map((m) => (
												<span
													key={m.userId || m.userCode}
													className="arp-member-chip"
												>
													<strong>{m.userCode}</strong> · {m.fullName}
													{m.sponsored && (
														<span style={{ marginLeft: "6px", fontSize: "11px", padding: "1px 6px", borderRadius: "4px", backgroundColor: "rgba(59, 130, 246, 0.15)", color: "var(--brand-blue, #3b82f6)", fontWeight: 600 }}>
															Bảo lãnh
														</span>
													)}
												</span>
											))}
										</div>
									</div>
								)}

							{/* Lý do từ chối (nếu có) */}
							{selectedDetail.status === "REJECTED" &&
								selectedDetail.rejectionReason && (
									<div className="arp-detail-item">
										<span
											className="arp-detail-label"
											style={{ color: "var(--theme-danger)" }}
										>
											Lý do từ chối
										</span>
										<div
											className="arp-detail-box"
											style={{
												borderColor: "var(--theme-danger-border)",
												background: "var(--theme-danger-bg)",
												color: "var(--theme-danger-text)",
											}}
										>
											{selectedDetail.rejectionReason}
										</div>
									</div>
								)}

							{/* Thông tin duyệt */}
							{selectedDetail.reviewedAt && (
								<div className="arp-detail-item">
									<span className="arp-detail-label">Thông tin duyệt</span>
									<div
										style={{
											fontSize: "12px",
											color: "var(--theme-text-secondary)",
										}}
									>
										Duyệt bởi:{" "}
										<strong>
											{selectedDetail.reviewerName || "Quản lý cơ sở"}
										</strong>{" "}
										vào lúc {formatDateTime(selectedDetail.reviewedAt)}
									</div>
								</div>
							)}
						</div>

						<div className="arp-modal__footer">
							<button
								type="button"
								className="arp-btn arp-btn--secondary"
								onClick={() => setSelectedDetail(null)}
							>
								Đóng
							</button>
						</div>
					</div>
				</div>
			)}

			{/* MODAL XÁC NHẬN HUỶ YÊU CẦU */}
			<Modal
				isOpen={Boolean(cancelItem)}
				onClose={() => !cancelling && setCancelItem(null)}
				title="Xác nhận hủy yêu cầu truy cập"
				subtitle="Thao tác này sẽ hủy bỏ yêu cầu của bạn và không thể hoàn tác."
				icon={AlertTriangle}
				iconVariant="danger"
				size="md"
				footer={
					<div
						style={{
							display: "flex",
							justifyContent: "flex-end",
							gap: "8px",
							width: "100%",
						}}
					>
						<Button
							variant="secondary"
							onClick={() => setCancelItem(null)}
							disabled={cancelling}
						>
							Đóng
						</Button>
						<Button
							variant="danger"
							onClick={handleConfirmCancel}
							loading={cancelling}
							disabled={cancelling}
						>
							Xác nhận hủy
						</Button>
					</div>
				}
			>
				{cancelError && (
					<div
						className="arp-banner arp-banner--error"
						style={{ marginBottom: "14px" }}
					>
						<AlertCircle size={16} />
						<span>{cancelError}</span>
					</div>
				)}
				{cancelItem && (
					<div
						style={{
							fontSize: "13px",
							lineHeight: "1.6",
							color: "var(--theme-text-secondary)",
						}}
					>
						<p style={{ margin: "0 0 10px 0" }}>
							Bạn có chắc chắn muốn hủy yêu cầu truy cập vào khu vực sau không?
						</p>
						<div
							style={{
								background: "var(--theme-bg-surface-elevated)",
								padding: "12px",
								borderRadius: "6px",
								border: "1px solid var(--theme-border)",
							}}
						>
							<div>
								<strong>Khu vực:</strong> {cancelItem.areaName}
								{formatLocation(cancelItem.building, cancelItem.floor) &&
									` (${formatLocation(cancelItem.building, cancelItem.floor)})`}
							</div>
							<div>
								<strong>Khung giờ:</strong>{" "}
								{formatRange(cancelItem.startTime, cancelItem.endTime)}
							</div>
							<div>
								<strong>Hình thức:</strong>{" "}
								{cancelItem.requestType === "GROUP"
									? `Theo nhóm (${cancelItem.members?.length || 0} thành viên)`
									: "Cá nhân"}
							</div>
							<div>
								<strong>Mục đích:</strong> {cancelItem.purpose}
							</div>
						</div>
					</div>
				)}
			</Modal>
		</div>
	);
}
