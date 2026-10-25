import React, { useState } from "react";
import {
	HelpCircle,
	CheckCircle2,
	XCircle,
	X,
	Radio,
	Save,
	Loader2,
	Eye,
	EyeOff,
	Copy,
	Check,
	Play,
	Square,
} from "lucide-react";

// UI-00: che "user:pass@" trong URL luồng khi hiển thị (giá trị gửi API giữ nguyên)
// tham lam tới "@" cuối cùng trước đường dẫn: mật khẩu có chứa "@" cũng bị che hết
const CREDENTIALS_IN_URL = /^([a-z][a-z0-9+.-]*:\/\/)[^/\s]*@/i;
const maskStreamCredentials = (value) =>
	typeof value === "string"
		? value.replace(CREDENTIALS_IN_URL, "$1••••@")
		: value;

export default function CameraStreamTab({
	camera,
	streamForm,
	setStreamForm,
	saving,
	onSubmit,
	testingConnection,
	testResult,
	onTestConnection,
	onCloseTestResult,
	startingStream,
	stoppingStream,
	onStartStream,
	onStopStream,
}) {
	const [editingPath, setEditingPath] = useState(false);
	const [showPlainPreviewPassword, setShowPlainPreviewPassword] =
		useState(false);
	const [copiedPreview, setCopiedPreview] = useState(false);

	const pathHasCredentials = CREDENTIALS_IN_URL.test(
		streamForm.mainStreamPath || "",
	);
	const showMaskedPath = pathHasCredentials && !editingPath;

	// Tự động ghép nối các trường cấu hình thành URL RTSP xem trước
	const computePreviewRtspUrl = (plainPassword = false) => {
		const rawHost = (streamForm.host || "").trim();
		if (!rawHost) return "";

		const rawPath = (streamForm.mainStreamPath || "").trim();
		if (rawPath.startsWith("rtsp://") || rawPath.startsWith("rtsps://")) {
			return rawPath;
		}
		if (rawHost.startsWith("rtsp://") || rawHost.startsWith("rtsps://")) {
			let formattedPath = rawPath;
			if (formattedPath && !formattedPath.startsWith("/")) {
				formattedPath = "/" + formattedPath;
			}
			return `${rawHost}${formattedPath}`;
		}

		let sb = "rtsp://";
		const rawUser = (streamForm.username || "").trim();
		const rawPass = (streamForm.credentialRef || "").trim();
		const isConfigured = camera?.streamConfig?.isPasswordConfigured;

		if (rawUser) {
			sb += encodeURIComponent(rawUser);
			if (rawPass) {
				sb += ":" + (plainPassword ? rawPass : "••••••••");
			} else if (isConfigured) {
				sb += ":" + "••••••••";
			}
			sb += "@";
		}

		sb += rawHost;
		if (streamForm.port) {
			sb += ":" + streamForm.port;
		}

		if (rawPath) {
			if (!rawPath.startsWith("/")) {
				sb += "/";
			}
			sb += rawPath;
		}

		return sb;
	};

	const previewRtspUrl = computePreviewRtspUrl(showPlainPreviewPassword);
	const previewRtspUrlForCopy = computePreviewRtspUrl(true);

	const handleCopyPreview = () => {
		if (!previewRtspUrl) return;
		const textToCopy = previewRtspUrlForCopy || previewRtspUrl;
		if (navigator.clipboard?.writeText) {
			navigator.clipboard
				.writeText(textToCopy)
				.then(() => {
					setCopiedPreview(true);
					setTimeout(() => setCopiedPreview(false), 2000);
				})
				.catch(() => {
					setCopiedPreview(true);
					setTimeout(() => setCopiedPreview(false), 2000);
				});
		} else {
			setCopiedPreview(true);
			setTimeout(() => setCopiedPreview(false), 2000);
		}
	};

	return (
		<form
			onSubmit={onSubmit}
			className="tab-form"
		>
			<div className="form-grid">
				<div className="form-group">
					<label>Địa chỉ IP/Host *</label>
					<input
						type="text"
						placeholder="192.168.1.50"
						value={streamForm.host}
						onChange={(e) =>
							setStreamForm({ ...streamForm, host: e.target.value })
						}
						required
					/>
				</div>

				<div className="form-group">
					<label>Cổng kết nối *</label>
					<input
						type="number"
						placeholder="554"
						value={streamForm.port}
						onChange={(e) =>
							setStreamForm({ ...streamForm, port: e.target.value })
						}
						required
					/>
				</div>

				<div className="form-group">
					<label
						style={{
							display: "flex",
							alignItems: "center",
							gap: "0.25rem",
						}}
					>
						Tài khoản camera
						<span
							data-tooltip="Tài khoản đăng nhập của camera để xem stream"
							className="help-icon-wrapper"
						>
							<HelpCircle
								size={14}
								className="help-icon"
							/>
						</span>
					</label>
					<input
						type="text"
						placeholder="admin"
						autoComplete="off"
						value={streamForm.username}
						onChange={(e) =>
							setStreamForm({
								...streamForm,
								username: e.target.value,
							})
						}
					/>
				</div>

				<div className="form-group">
					<label
						style={{
							display: "flex",
							alignItems: "center",
							gap: "0.25rem",
						}}
					>
						Mật khẩu RTSP / Khóa bảo mật
						<span
							data-tooltip="Mật khẩu tài khoản camera (được mã hóa AES-256 an toàn)"
							className="help-icon-wrapper"
						>
							<HelpCircle
								size={14}
								className="help-icon"
							/>
						</span>
					</label>
					<input
						type="password"
						autoComplete="new-password"
						placeholder={
							camera?.streamConfig?.isPasswordConfigured
								? "•••••••• (Đã mã hóa và lưu bảo mật)"
								: "Nhập mật khẩu RTSP"
						}
						value={streamForm.credentialRef}
						onChange={(e) =>
							setStreamForm({
								...streamForm,
								credentialRef: e.target.value,
							})
						}
					/>
				</div>

				<div className="form-group col-span-2">
					<label>Đuôi đường dẫn Main Stream *</label>
					{showMaskedPath ? (
						<div
							style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}
						>
							<input
								type="text"
								value={maskStreamCredentials(streamForm.mainStreamPath)}
								readOnly
								aria-label="Main Stream Path (đã che tài khoản)"
								style={{ flex: 1, minWidth: 0 }}
							/>
							<button
								type="button"
								className="btn-test-stream"
								onClick={() => setEditingPath(true)}
								title="Hiện đường dẫn đầy đủ để sửa"
							>
								Sửa
							</button>
						</div>
					) : (
						<input
							type="text"
							placeholder="/Streaming/Channels/101"
							value={streamForm.mainStreamPath}
							onChange={(e) =>
								setStreamForm({
									...streamForm,
									mainStreamPath: e.target.value,
								})
							}
							required
						/>
					)}
				</div>

				{/* Trường URL luồng RTSP xem trước (Tự động tạo) */}
				<div className="form-group col-span-2 stream-preview-container">
					<div className="stream-preview-header">
						<label className="stream-preview-label">
							<Radio
								size={14}
								className="text-sky-400"
							/>
							<span>Xem trước URL luồng RTSP (Tự động ghép)</span>
						</label>
					</div>

					<div className="stream-preview-input-wrap">
						<input
							type="text"
							readOnly
							value={
								previewRtspUrl ||
								"rtsp://[tài_khoản[:mật_khẩu]@]địa_chỉ_ip[:cổng][/đường_dẫn]"
							}
							className={`stream-preview-input ${!previewRtspUrl ? "stream-preview-input--placeholder" : ""}`}
							placeholder="Nhập IP/Host bên trên để xem trước URL"
							aria-label="URL luồng RTSP xem trước tự động ghép"
						/>
						{previewRtspUrl && (
							<div className="stream-preview-actions">
								{(streamForm.credentialRef ||
									camera?.streamConfig?.isPasswordConfigured) && (
									<button
										type="button"
										className="stream-preview-btn"
										onClick={() =>
											setShowPlainPreviewPassword(!showPlainPreviewPassword)
										}
										title={
											showPlainPreviewPassword
												? "Ẩn mật khẩu trong preview"
												: "Hiện mật khẩu trong preview"
										}
									>
										{showPlainPreviewPassword ? (
											<EyeOff size={15} />
										) : (
											<Eye size={15} />
										)}
									</button>
								)}
								<button
									type="button"
									className="stream-preview-btn stream-preview-btn--copy"
									onClick={handleCopyPreview}
									title="Sao chép URL luồng RTSP"
								>
									{copiedPreview ? (
										<>
											<Check
												size={15}
												className="text-emerald-400"
											/>
											<span className="copy-tooltip">Đã chép!</span>
										</>
									) : (
										<Copy size={15} />
									)}
								</button>
							</div>
						)}
					</div>
				</div>
			</div>

			{/* Test Connection Result Alert */}
			{testResult && (
				<div
					className={`test-conn-alert ${
						testResult.success
							? "test-conn-alert--success"
							: "test-conn-alert--error"
					}`}
				>
					{testResult.success ? (
						<CheckCircle2
							size={18}
							className="text-emerald-400 shrink-0"
						/>
					) : (
						<XCircle
							size={18}
							className="text-rose-400 shrink-0"
						/>
					)}
					<div className="test-conn-alert-content">
						<span>{testResult.message}</span>
					</div>
					<button
						type="button"
						onClick={onCloseTestResult}
						className="test-conn-alert-close"
						title="Đóng"
					>
						<X size={14} />
					</button>
				</div>
			)}

			<div className="form-actions stream-form-actions">
				<button
					type="button"
					className="btn-test-stream"
					onClick={onTestConnection}
					disabled={
						testingConnection || saving || startingStream || stoppingStream
					}
					title="Kiểm tra tín hiệu cổng RTSP và đo độ trễ mạng"
				>
					{testingConnection ? (
						<>
							<Loader2
								className="animate-spin"
								size={16}
							/>
							<span>Đang thử kết nối...</span>
						</>
					) : (
						<>
							<Radio size={16} />
							<span>Thử kết nối</span>
						</>
					)}
				</button>

				{camera?.status !== "DECOMMISSIONED" &&
					(camera?.operationalStatus === "ONLINE" ? (
						<button
							type="button"
							className="btn-stop-stream"
							onClick={onStopStream}
							disabled={
								stoppingStream || startingStream || saving || testingConnection
							}
							title="Ngắt Stream phát trực tiếp và dừng worker AI phân tích"
						>
							{stoppingStream ? (
								<>
									<Loader2
										className="animate-spin"
										size={16}
									/>
									<span>Đang Ngắt Stream...</span>
								</>
							) : (
								<>
									<Square size={16} />
									<span>Ngắt Stream</span>
								</>
							)}
						</button>
					) : (
						<button
							type="button"
							className="btn-start-stream"
							onClick={onStartStream}
							disabled={
								startingStream || stoppingStream || saving || testingConnection
							}
							title="Kết nối Stream phát trực tiếp và khởi động worker AI phân tích"
						>
							{startingStream ? (
								<>
									<Loader2
										className="animate-spin"
										size={16}
									/>
									<span>Đang kích hoạt...</span>
								</>
							) : (
								<>
									<Play size={16} />
									<span>Kết nối Stream</span>
								</>
							)}
						</button>
					))}

				<button
					type="submit"
					className="btn-save"
					disabled={
						saving || testingConnection || startingStream || stoppingStream
					}
				>
					{saving ? (
						<Loader2
							className="animate-spin"
							size={16}
						/>
					) : (
						<Save size={16} />
					)}
					<span title="Lưu stream">Lưu</span>
				</button>
			</div>
		</form>
	);
}
