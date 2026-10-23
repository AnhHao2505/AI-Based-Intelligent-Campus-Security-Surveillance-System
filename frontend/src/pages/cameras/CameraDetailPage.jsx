import { useState, useEffect } from "react";
import { useParams, useNavigate, useSearchParams } from "react-router-dom";
import {
	ArrowLeft,
	Video,
	Info,
	Power,
	PowerOff,
	MapPin,
	Layers,
	Activity,
	History,
} from "lucide-react";
import {
	fetchCameraDetail,
	updateCamera,
	decommissionCamera,
	reactivateCamera,
	upsertStreamConfig,
	fetchHealthLogs,
	captureSnapshot,
	startStream,
	stopStream,
	testConnection,
	updateRoiGeometry,
} from "../../services/cameraService";
import { getAreas } from "../../services/areaService";
import { toast } from "sonner";
import RoiEditorModal from "../../components/camera/RoiEditorModal";
import CameraGeneralTab from "../../components/camera/CameraGeneralTab";
import CameraStreamTab from "../../components/camera/CameraStreamTab";
import CameraSurveillanceTab from "../../components/camera/CameraSurveillanceTab";
import { formatImageUrl } from "../../utils/roiUtils";
import CameraAccessLogsTab from "../../components/camera/CameraAccessLogsTab";
import CameraHealthLogs from "../../components/camera/CameraHealthLogs";
import "../../styles/CameraDetailPage.css";
import {
	Modal,
	Button,
	LoadingState,
	EmptyState,
	ErrorState,
} from "../../components/ui";
import { formatLocation } from "../../utils/formatLocation";

export default function CameraDetailPage() {
	const { id } = useParams();
	const navigate = useNavigate();
	const [searchParams, setSearchParams] = useSearchParams();
	const tabParam = searchParams.get("tab");
	const [activeTab, setActiveTabState] = useState(
		["general", "stream", "surveillance", "access-logs"].includes(tabParam)
			? tabParam
			: "general",
	);

	const setActiveTab = (tab) => {
		setActiveTabState(tab);
		setSearchParams(
			(prev) => {
				const next = new URLSearchParams(prev);
				next.set("tab", tab);
				return next;
			},
			{ replace: true },
		);
	};

	useEffect(() => {
		const currentTab = searchParams.get("tab");
		if (
			currentTab &&
			["general", "stream", "surveillance", "access-logs"].includes(currentTab)
		) {
			setActiveTabState(currentTab);
		}
	}, [searchParams]);

	const [loading, setLoading] = useState(true);
	const [saving, setSaving] = useState(false);
	// Xác nhận trước khi tắt camera (trước đây bấm "Tắt camera" là gọi API ngay)
	const [confirmDecommissionOpen, setConfirmDecommissionOpen] = useState(false);
	const [error, setError] = useState(null);
	const [successMsg, setSuccessMsg] = useState(null);

	const [logs, setLogs] = useState([]);
	const [logPage, setLogPage] = useState(0);
	const [logTotalPages, setLogTotalPages] = useState(0);
	const [logsLoading, setLogsLoading] = useState(false);
	// Lỗi tải: tách khỏi `error` (thông báo thao tác) để không hiện "Không tìm thấy camera" khi chỉ là lỗi mạng / 500
	const [loadError, setLoadError] = useState(null);
	const [logsError, setLogsError] = useState(null);

	const [testingConnection, setTestingConnection] = useState(false);
	const [testResult, setTestResult] = useState(null);

	const [startingStream, setStartingStream] = useState(false);
	const [stoppingStream, setStoppingStream] = useState(false);
	const [capturingSnapshot, setCapturingSnapshot] = useState(false);
	const [refreshingLive, setRefreshingLive] = useState(false);
	const [liveSnapshot, setLiveSnapshot] = useState(null);
	const [compareMode, setCompareMode] = useState(false);
	const [roiModalOpen, setRoiModalOpen] = useState(false);
	const [activeSnapshotForModal, setActiveSnapshotForModal] = useState(null);
	const [surveillanceViewMode, setSurveillanceViewMode] = useState("snapshot");

	const [camera, setCamera] = useState(null);
	const [availableAreas, setAvailableAreas] = useState([]);
	const [generalForm, setGeneralForm] = useState({
		name: "",
		areaId: "",
	});

	const [streamForm, setStreamForm] = useState({
		host: "",
		port: "",
		username: "",
		credentialRef: "",
		mainStreamPath: "",
	});

	useEffect(() => {
		getAreas({ size: 200, isActive: true })
			.then((res) => {
				const list =
					res?.content || res?.areas || (Array.isArray(res) ? res : []);
				setAvailableAreas(list);
			})
			.catch((err) => console.error("Failed to load areas:", err));
	}, []);

	const loadCameraDetails = async () => {
		setLoading(true);
		setError(null);
		setLoadError(null);
		try {
			const data = await fetchCameraDetail(id);
			setCamera(data);

			setGeneralForm({
				name: data.name || "",
				areaId: data.assignedArea?.id || data.assignedAreas?.[0]?.id || "",
			});

			if (data.streamConfig) {
				setStreamForm({
					host: data.streamConfig.host || "",
					port:
						data.streamConfig.port !== null &&
						data.streamConfig.port !== undefined
							? data.streamConfig.port.toString()
							: "",
					username: data.streamConfig.username || "",
					credentialRef: "",
					mainStreamPath: data.streamConfig.mainStreamPath || "",
				});
			}
		} catch (err) {
			console.error("Failed to load camera details:", err);
			setError("Lỗi tải thông tin chi tiết camera.");
			setLoadError({
				status: err?.status,
				message: err?.message || "Lỗi tải thông tin chi tiết camera.",
			});
		} finally {
			setLoading(false);
		}
	};

	const loadLogs = async () => {
		setLogsLoading(true);
		setLogsError(null);
		try {
			const data = await fetchHealthLogs(id, { page: logPage, size: 5 });
			setLogs(data.content || []);
			setLogTotalPages(data.totalPages || 0);
		} catch (err) {
			console.error("Failed to load health logs:", err);
			setLogsError(err?.message || "Không tải được nhật ký kết nối.");
		} finally {
			setLogsLoading(false);
		}
	};

	useEffect(() => {
		loadCameraDetails();
	}, [id]);

	useEffect(() => {
		loadLogs();
	}, [id, logPage]);

	const showNotification = (msg) => {
		toast.success(msg);
	};

	const showError = (msg) => {
		toast.error(msg);
	};

	const handleTestConnection = async () => {
		setTestingConnection(true);
		setTestResult(null);
		try {
			const payload = {
				host: streamForm.host ? streamForm.host.trim() : "",
				port: streamForm.port ? parseInt(streamForm.port, 10) : 554,
				username: streamForm.username ? streamForm.username.trim() : "",
				mainStreamPath: streamForm.mainStreamPath
					? streamForm.mainStreamPath.trim()
					: "",
			};
			if (streamForm.credentialRef && streamForm.credentialRef.trim()) {
				payload.credentialRef = streamForm.credentialRef.trim();
			}
			const res = await testConnection(id, payload);
			if (res && res.success) {
				setTestResult({
					success: true,
					message: res.message || "Kết nối RTSP thành công!",
					latencyMs: res.latencyMs,
				});
			} else {
				const msg =
					res?.message ||
					"Không thể kết nối đến camera: Vui lòng kiểm tra lại địa chỉ IP, cổng mạng hoặc tài khoản RTSP.";
				setTestResult({ success: false, message: msg });
			}
		} catch (err) {
			console.error("Failed to test connection:", err);
			const msg =
				err.message ||
				"Lỗi kiểm tra kết nối stream: Vui lòng kiểm tra kết nối mạng và thiết lập camera.";
			setTestResult({ success: false, message: msg });
		} finally {
			setTestingConnection(false);
		}
	};

	const handleStartStream = async () => {
		setStartingStream(true);
		setError(null);
		try {
			const res = await startStream(id);
			if (res && res.success) {
				setCamera((prev) => ({
					...prev,
					operationalStatus: "ONLINE",
				}));
				showNotification(
					"Kết nối Stream phát trực tiếp thành công! Camera đã chuyển sang trạng thái ONLINE.",
				);
				loadLogs();
			} else {
				showError(res?.message || "Không thể Kết nối Stream phát trực tiếp.");
			}
		} catch (err) {
			console.error("Failed to start stream:", err);
			showError(err.message || "Không thể Kết nối Stream phát trực tiếp.");
		} finally {
			setStartingStream(false);
		}
	};

	const handleStopStream = async () => {
		setStoppingStream(true);
		setError(null);
		try {
			const res = await stopStream(id);
			if (res && res.success) {
				setCamera((prev) => ({
					...prev,
					operationalStatus: "OFFLINE",
				}));
				showNotification(
					"Đã Ngắt Stream phát trực tiếp của camera. Camera đã chuyển sang trạng thái OFFLINE.",
				);
				loadLogs();
			} else {
				showError(res?.message || "Không thể Ngắt Stream phát trực tiếp.");
			}
		} catch (err) {
			console.error("Failed to stop stream:", err);
			showError(err.message || "Không thể Ngắt Stream phát trực tiếp.");
		} finally {
			setStoppingStream(false);
		}
	};

	const handleCaptureNewSnapshot = async () => {
		setCapturingSnapshot(true);
		setError(null);
		try {
			const res = await captureSnapshot(id);
			if (res && res.success) {
				showNotification("Đã trích xuất khung hình mới thành công!");
				setActiveSnapshotForModal({
					data: res.snapshotBase64,
					width: res.width || 1920,
					height: res.height || 1080,
				});
				setRoiModalOpen(true);
			} else {
				showError(res?.errorMessage || "Không thể chụp snapshot từ camera.");
			}
		} catch (err) {
			console.error("Failed to capture snapshot:", err);
			showError(err.message || "Lỗi chụp snapshot từ camera.");
		} finally {
			setCapturingSnapshot(false);
		}
	};

	const handleCaptureFromLiveStream = (snapshotData) => {
		if (!snapshotData || !snapshotData.base64) {
			handleCaptureNewSnapshot();
			return;
		}
		showNotification("Đã chụp khung hình trực tiếp thành công!");
		setActiveSnapshotForModal({
			data: snapshotData.base64,
			width: snapshotData.width || 1920,
			height: snapshotData.height || 1080,
		});
		setRoiModalOpen(true);
	};

	const handleEditCurrentRoi = () => {
		const rawRefUrl =
			camera?.roiGeometry?.reference_snapshot_url ||
			camera?.roiGeometry?.referenceSnapshotUrl;
		const refUrl = formatImageUrl(rawRefUrl);
		const refWidth =
			camera?.roiGeometry?.reference_snapshot_width ||
			camera?.roiGeometry?.referenceSnapshotWidth ||
			1920;
		const refHeight =
			camera?.roiGeometry?.reference_snapshot_height ||
			camera?.roiGeometry?.referenceSnapshotHeight ||
			1080;

		if (!refUrl) {
			showError("Chưa có ảnh chụp tham chiếu. Vui lòng bấm 'Chụp ảnh mới'.");
			return;
		}

		setActiveSnapshotForModal({
			data: refUrl,
			width: refWidth,
			height: refHeight,
		});
		setRoiModalOpen(true);
	};

	const handleCheckDrift = async () => {
		setRefreshingLive(true);
		setError(null);
		try {
			const res = await captureSnapshot(id);
			if (res && res.success) {
				setLiveSnapshot(res);
				setCompareMode(true);
				showNotification(
					"Đã trích xuất khung hình trực tiếp để kiểm tra sai lệch góc quan sát camera.",
				);
			} else {
				showError(res?.errorMessage || "Không thể lấy khung hình trực tiếp.");
			}
		} catch (err) {
			console.error("Failed to capture live frame for drift check:", err);
			showError(err.message || "Lỗi khi lấy khung hình kiểm tra sai lệch.");
		} finally {
			setRefreshingLive(false);
		}
	};

	const handleCloseCompare = () => {
		setCompareMode(false);
		setLiveSnapshot(null);
	};

	const handleSaveRoi = async (roiGeometry, snapshotMeta) => {
		const updated = await updateRoiGeometry(id, roiGeometry, snapshotMeta);
		const newRoi = updated?.roiGeometry || updated;
		setCamera((prev) => {
			const refUrl =
				newRoi?.referenceSnapshotUrl ||
				newRoi?.reference_snapshot_url ||
				prev?.roiGeometry?.referenceSnapshotUrl ||
				prev?.roiGeometry?.reference_snapshot_url;
			return {
				...prev,
				roiGeometry: {
					...prev?.roiGeometry,
					...newRoi,
					referenceSnapshotUrl: refUrl,
					reference_snapshot_url: refUrl,
				},
			};
		});
		setCompareMode(false);
		setLiveSnapshot(null);
		setSurveillanceViewMode("snapshot");
		showNotification("Cấu hình vùng giám sát (ROI) đã được lưu thành công!");
		loadCameraDetails();
	};

	const handleToggleStatus = async () => {
		if (!camera) return;
		setSaving(true);
		try {
			let updated;
			if (camera.status === "DECOMMISSIONED") {
				updated = await reactivateCamera(camera.id);
				showNotification("Đã kích hoạt lại camera thành công");
			} else {
				updated = await decommissionCamera(camera.id);
				showNotification("Đã dừng hoạt động camera thành công");
			}
			setCamera(updated);
		} catch (err) {
			showError(err.message || "Thay đổi trạng thái thất bại.");
		} finally {
			setSaving(false);
		}
	};

	const handleGeneralSubmit = async (e) => {
		e.preventDefault();
		setSaving(true);
		try {
			const payload = {
				name: generalForm.name,
				areaId: generalForm.areaId,
			};
			const updated = await updateCamera(id, payload);
			setCamera(updated);
			showNotification("Cập nhật thông tin camera thành công!");
		} catch (err) {
			showError(err.message || "Cập nhật thông tin thất bại.");
		} finally {
			setSaving(false);
		}
	};

	const handleStreamSubmit = async (e) => {
		e.preventDefault();
		setSaving(true);
		try {
			const payload = {
				host: streamForm.host,
				port: streamForm.port ? parseInt(streamForm.port, 10) : 554,
				username: streamForm.username,
				mainStreamPath: streamForm.mainStreamPath,
			};
			if (streamForm.credentialRef) {
				payload.credentialRef = streamForm.credentialRef;
			}
			const updated = await upsertStreamConfig(id, payload);
			const newStatus =
				updated?.operationalStatus || camera?.operationalStatus || "OFFLINE";
			setCamera((prev) => ({
				...prev,
				streamConfig: updated,
				operationalStatus: newStatus,
			}));
			setStreamForm((prev) => ({
				...prev,
				credentialRef: "",
			}));
			toast.success("Lưu cấu hình stream thành công!");
			loadCameraDetails();
			loadLogs();
		} catch (err) {
			toast.error(err.message || "Cập nhật cấu hình stream thất bại.");
		} finally {
			setSaving(false);
		}
	};

	if (loading) {
		return (
			<div className="camera-detail-page">
				<LoadingState text="Đang tải thông tin chi tiết camera..." />
			</div>
		);
	}

	if (!camera) {
		// Lỗi mạng / 500 -> ErrorState + Thử lại; chỉ 404 (hoặc không có lỗi) mới là "không tìm thấy"
		const isLoadFailure =
			loadError && loadError.status !== 404 && loadError.status !== 403;
		return (
			<div className="camera-detail-page">
				{isLoadFailure ? (
					<ErrorState
						title="Không tải được thông tin camera"
						message={loadError.message}
						onRetry={loadCameraDetails}
					/>
				) : (
					<EmptyState
						title="Không tìm thấy camera"
						description="Camera này không tồn tại hoặc bạn không có quyền truy cập."
					/>
				)}
				<div style={{ display: "flex", justifyContent: "center" }}>
					<button
						className="btn-back"
						onClick={() => navigate("/admin/cameras")}
					>
						<ArrowLeft size={16} /> Quay lại danh sách
					</button>
				</div>
			</div>
		);
	}

	const isDecommissioned = camera.status === "DECOMMISSIONED";

	return (
		<div className="camera-detail-page">
			<div className="breadcrumb">
				<button
					className="btn-back"
					onClick={() => navigate("/cameras")}
				>
					<ArrowLeft size={16} /> Danh sách Camera
				</button>
			</div>

			<div className="detail-header-card">
				<div className="detail-header-left">
					<div className="camera-icon-wrapper">
						<Video size={28} />
					</div>
					<div>
						<div className="detail-title-row">
							<h2>{camera.name}</h2>
							<span className="font-mono code-tag">{camera.cameraCode}</span>
						</div>
						<div className="detail-badges-row">
							<span
								className={`status-badge badge-${camera.status.toLowerCase()}`}
							>
								{camera.status === "ACTIVE" ? "ACTIVE" : "INACTIVE"}
							</span>
							<span
								className={`status-badge op-badge-${camera.operationalStatus.toLowerCase()}`}
							>
								● {camera.operationalStatus}
							</span>
							{camera.assignedAreas && camera.assignedAreas.length > 0 ? (
								camera.assignedAreas.map((area) => (
									<span
										key={area.id}
										className="location-tag"
										title={area.name}
									>
										<MapPin
											size={12}
											style={{
												display: "inline",
												verticalAlign: "middle",
												marginRight: "4px",
											}}
										/>
										{area.name}
										{formatLocation(area.building, area.floor)
											? ` (${formatLocation(area.building, area.floor)})`
											: ""}
									</span>
								))
							) : (
								<span
									className="location-tag"
									style={{ opacity: 0.7 }}
								>
									Chưa gán khu vực
								</span>
							)}
						</div>
					</div>
				</div>
			</div>

			<div className="detail-grid">
				<div className="config-card">
					<div className="tabs-navigation">
						<button
							className={`tab-btn ${
								activeTab === "general" ? "tab-btn--active" : ""
							}`}
							onClick={() => setActiveTab("general")}
						>
							<Info size={16} />
							<span>Thông tin chung</span>
						</button>
						<button
							className={`tab-btn ${
								activeTab === "stream" ? "tab-btn--active" : ""
							}`}
							onClick={() => setActiveTab("stream")}
						>
							<Video size={16} />
							<span>Cấu hình Stream</span>
						</button>
						<button
							className={`tab-btn ${
								activeTab === "surveillance" ? "tab-btn--active" : ""
							}`}
							onClick={() => setActiveTab("surveillance")}
						>
							<Layers size={16} />
							<span>Vùng Giám Sát</span>
						</button>
						<button
							className={`tab-btn ${
								activeTab === "access-logs" ? "tab-btn--active" : ""
							}`}
							onClick={() => setActiveTab("access-logs")}
						>
							<Activity size={16} />
							<span>Nhật ký ra vào</span>
						</button>
					</div>

					<div className="tab-content">
						<div
							style={{ display: activeTab === "general" ? "block" : "none" }}
						>
							<CameraGeneralTab
								camera={camera}
								generalForm={generalForm}
								setGeneralForm={setGeneralForm}
								availableAreas={availableAreas}
								saving={saving}
								onSubmit={handleGeneralSubmit}
							/>
						</div>

						<div style={{ display: activeTab === "stream" ? "block" : "none" }}>
							<CameraStreamTab
								camera={camera}
								streamForm={streamForm}
								setStreamForm={setStreamForm}
								saving={saving}
								onSubmit={handleStreamSubmit}
								testingConnection={testingConnection}
								testResult={testResult}
								onTestConnection={handleTestConnection}
								onCloseTestResult={() => setTestResult(null)}
								startingStream={startingStream}
								stoppingStream={stoppingStream}
								onStartStream={handleStartStream}
								onStopStream={handleStopStream}
							/>
						</div>

						<div
							style={{
								display: activeTab === "surveillance" ? "block" : "none",
							}}
						>
							<CameraSurveillanceTab
								camera={camera}
								capturingSnapshot={capturingSnapshot}
								refreshingLive={refreshingLive}
								compareMode={compareMode}
								liveSnapshot={liveSnapshot}
								onCaptureNewSnapshot={handleCaptureNewSnapshot}
								onEditCurrentRoi={handleEditCurrentRoi}
								onCheckDrift={handleCheckDrift}
								onCloseCompare={handleCloseCompare}
								onCaptureFromLiveStream={handleCaptureFromLiveStream}
								viewMode={surveillanceViewMode}
								onViewModeChange={setSurveillanceViewMode}
								onError={showError}
							/>
						</div>

						<div
							style={{
								display: activeTab === "access-logs" ? "block" : "none",
							}}
						>
							<CameraAccessLogsTab camera={camera} />
						</div>
					</div>
				</div>

				<CameraHealthLogs
					logs={logs}
					loading={logsLoading}
					page={logPage}
					totalPages={logTotalPages}
					onPageChange={setLogPage}
					onRefresh={loadLogs}
					error={logsError}
				/>
			</div>

			<RoiEditorModal
				isOpen={roiModalOpen}
				onClose={() => setRoiModalOpen(false)}
				snapshotBase64={activeSnapshotForModal?.data}
				snapshotWidth={activeSnapshotForModal?.width || 1920}
				snapshotHeight={activeSnapshotForModal?.height || 1080}
				initialRoiGeometry={camera?.roiGeometry}
				availableAreas={camera?.assignedAreas || []}
				onSave={handleSaveRoi}
			/>
		</div>
	);
}
