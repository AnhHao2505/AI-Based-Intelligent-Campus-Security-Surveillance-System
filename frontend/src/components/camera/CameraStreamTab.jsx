import React, { useState } from "react";
import {
  HelpCircle,
  CheckCircle2,
  XCircle,
  X,
  Radio,
  Save,
  Loader2,
} from "lucide-react";

// UI-00: che "user:pass@" trong URL luồng khi hiển thị (giá trị gửi API giữ nguyên)
// tham lam tới "@" cuối cùng trước đường dẫn: mật khẩu có chứa "@" cũng bị che hết
const CREDENTIALS_IN_URL = /^([a-z][a-z0-9+.-]*:\/\/)[^/\s]*@/i;
const maskStreamCredentials = (value) =>
  typeof value === "string" ? value.replace(CREDENTIALS_IN_URL, "$1••••@") : value;

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
}) {
  const [editingPath, setEditingPath] = useState(false);
  const pathHasCredentials = CREDENTIALS_IN_URL.test(streamForm.mainStreamPath || "");
  const showMaskedPath = pathHasCredentials && !editingPath;

  return (
    <form onSubmit={onSubmit} className="tab-form">
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
              <HelpCircle size={14} className="help-icon" />
            </span>
          </label>
          <input
            type="text"
            placeholder="admin"
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
              <HelpCircle size={14} className="help-icon" />
            </span>
          </label>
          <input
            type="password"
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
          <label>Main Stream Path *</label>
          {showMaskedPath ? (
            <div style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
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
      </div>

      {/* Test Connection Result Alert */}
      {testResult && (
        <div
          className={`test-conn-alert ${
            testResult.success ? "test-conn-alert--success" : "test-conn-alert--error"
          }`}
        >
          {testResult.success ? (
            <CheckCircle2 size={18} className="text-emerald-400 shrink-0" />
          ) : (
            <XCircle size={18} className="text-rose-400 shrink-0" />
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
          disabled={testingConnection || saving}
          title="Kiểm tra tín hiệu luồng RTSP mà không thay đổi trạng thái hoạt động"
        >
          {testingConnection ? (
            <>
              <Loader2 className="animate-spin" size={16} />
              <span>Đang thử kết nối...</span>
            </>
          ) : (
            <>
              <Radio size={16} />
              <span>Thử kết nối</span>
            </>
          )}
        </button>

        <button
          type="submit"
          className="btn-save"
          disabled={saving || testingConnection}
        >
          {saving ? (
            <Loader2 className="animate-spin" size={16} />
          ) : (
            <Save size={16} />
          )}
          <span>Lưu luồng Stream</span>
        </button>
      </div>
    </form>
  );
}
