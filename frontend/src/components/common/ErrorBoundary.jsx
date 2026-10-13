import React from "react";
import { AlertTriangle, RefreshCw } from "lucide-react";

export default class ErrorBoundary extends React.Component {
	constructor(props) {
		super(props);
		this.state = { hasError: false, error: null, errorInfo: null };
	}

	static getDerivedStateFromError(error) {
		return { hasError: true, error };
	}

	componentDidCatch(error, errorInfo) {
		console.error("Uncaught Error in Component:", error, errorInfo);
		if (error?.message?.includes("JSON") || error?.message?.includes("undefined")) {
			try {
				const u = localStorage.getItem("user");
				if (!u || u === "undefined" || u === "null") {
					localStorage.removeItem("user");
				}
			} catch (_) {}
		}
		this.setState({ errorInfo });
	}

	handleReload = () => {
		this.setState({ hasError: false, error: null, errorInfo: null });
		window.location.reload();
	};

	handleResetAuth = () => {
		try {
			localStorage.removeItem("user");
			localStorage.removeItem("accessToken");
		} catch (_) {}
		this.setState({ hasError: false, error: null, errorInfo: null });
		window.location.href = "/login";
	};

	render() {
		if (this.state.hasError) {
			return (
				<div
					style={{
						display: "flex",
						flexDirection: "column",
						alignItems: "center",
						justifyContent: "center",
						minHeight: "420px",
						padding: "32px",
						textAlign: "center",
						background: "var(--theme-bg-surface, #ffffff)",
						borderRadius: "12px",
						border: "1px solid var(--theme-border, #e2e8f0)",
						margin: "20px auto",
						maxWidth: "600px",
						boxShadow: "0 4px 20px rgba(0,0,0,0.08)",
					}}
				>
					<div
						style={{
							width: "48px",
							height: "48px",
							borderRadius: "50%",
							background: "#fee2e2",
							color: "#dc2626",
							display: "flex",
							alignItems: "center",
							justifyContent: "center",
							marginBottom: "16px",
						}}
					>
						<AlertTriangle size={24} />
					</div>
					<h3
						style={{
							margin: "0 0 8px",
							fontSize: "18px",
							fontWeight: "700",
							color: "var(--theme-text-primary, #0f172a)",
						}}
					>
						Đã xảy ra lỗi hiển thị
					</h3>
					<p
						style={{
							margin: "0 0 16px",
							fontSize: "13px",
							color: "var(--theme-text-secondary, #64748b)",
							maxWidth: "450px",
							lineHeight: 1.5,
						}}
					>
						{this.state.error?.message ||
							"Có sự cố không mong muốn trong quá trình nạp giao diện."}
					</p>
					<div style={{ display: "flex", gap: "10px", flexWrap: "wrap", justifyContent: "center" }}>
						<button
							type="button"
							onClick={this.handleReload}
							style={{
								display: "inline-flex",
								alignItems: "center",
								gap: "8px",
								padding: "8px 18px",
								fontSize: "13px",
								fontWeight: "600",
								color: "#ffffff",
								background: "var(--theme-primary, #2563eb)",
								border: "none",
								borderRadius: "8px",
								cursor: "pointer",
							}}
						>
							<RefreshCw size={14} />
							Tải lại trang
						</button>
						<button
							type="button"
							onClick={this.handleResetAuth}
							style={{
								display: "inline-flex",
								alignItems: "center",
								gap: "8px",
								padding: "8px 18px",
								fontSize: "13px",
								fontWeight: "600",
								color: "#374151",
								background: "#f3f4f6",
								border: "1px solid #d1d5db",
								borderRadius: "8px",
								cursor: "pointer",
							}}
						>
							Đăng nhập lại
						</button>
					</div>
				</div>
			);
		}

		return this.props.children;
	}
}
