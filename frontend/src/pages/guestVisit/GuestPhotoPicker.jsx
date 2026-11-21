import React, { useEffect, useState } from "react";
import { validateGuestPhotoFile, GUEST_PHOTO_FALLBACK_MAX_KB } from "../../utils/guestHelpers";

/**
 * A1: chọn ảnh khuôn mặt của một khách (JPG / PNG, tối đa maxKb, có xem trước) và ô xác nhận khách đã đồng ý chụp ảnh
 * theo thông báo phiên bản consentVersion. Dùng ở form tạo lượt (mỗi khách một ô) và hộp thay ảnh.
 *
 * @param {Object} props
 * @param {string} props.id - tiền tố id cho input (gắn label / aria)
 * @param {string} props.guestLabel - tên hiển thị trong aria-label (vd. "khách 1")
 * @param {{ file: File|null, consent: boolean }} props.value
 * @param {(next: { file: File|null, consent: boolean }) => void} props.onChange
 * @param {number} [props.maxKb]
 * @param {string|null} props.consentVersion - null: chưa tải được phiên bản thông báo -> khoá ô đồng ý
 * @param {boolean} [props.disabled]
 */
export default function GuestPhotoPicker({
	id,
	guestLabel,
	value,
	onChange,
	maxKb = GUEST_PHOTO_FALLBACK_MAX_KB,
	consentVersion,
	disabled = false,
}) {
	const file = value?.file || null;
	const [previewUrl, setPreviewUrl] = useState(null);
	const [fileError, setFileError] = useState(null);

	useEffect(() => {
		if (!file) {
			setPreviewUrl(null);
			return undefined;
		}
		const url = URL.createObjectURL(file);
		setPreviewUrl(url);
		return () => URL.revokeObjectURL(url);
	}, [file]);

	const handleFile = (e) => {
		const picked = e.target.files?.[0] || null;
		if (!picked) return;
		const error = validateGuestPhotoFile(picked, maxKb);
		setFileError(error);
		if (error) {
			e.target.value = "";
		}
		onChange({ ...value, file: error ? null : picked });
	};

	return (
		<div className="guest-visit__photo-picker">
			<div className="guest-visit__field">
				<label className="guest-visit__label" htmlFor={`${id}-file`}>
					Ảnh khuôn mặt * (JPG, PNG, tối đa {maxKb} KB)
				</label>
				<input
					id={`${id}-file`}
					className="guest-visit__file-input"
					type="file"
					accept="image/jpeg,image/png"
					aria-label={`Ảnh khuôn mặt của ${guestLabel}`}
					onChange={handleFile}
					disabled={disabled}
				/>
				{fileError ? (
					<span className="guest-visit__hint guest-visit__hint--error" role="alert">
						{fileError}
					</span>
				) : (
					<span className="guest-visit__hint">Ảnh rõ mặt, chỉ một người trong ảnh.</span>
				)}
				<label className="guest-visit__consent">
					<input
						type="checkbox"
						checked={Boolean(value?.consent)}
						onChange={(e) => onChange({ ...value, consent: e.target.checked })}
						disabled={disabled || !consentVersion}
						aria-label={`${guestLabel} đã đồng ý chụp ảnh`}
					/>
					<span>
						Khách đã đồng ý chụp ảnh để nhận diện theo thông báo phiên bản <strong>{consentVersion || "—"}</strong>.
					</span>
				</label>
			</div>
			{previewUrl && (
				<figure className="guest-visit__photo-preview">
					<img src={previewUrl} alt={`Xem trước ảnh của ${guestLabel}`} />
					<figcaption className="guest-visit__muted">
						{file?.name} ({(file.size / 1024).toFixed(0)} KB)
					</figcaption>
				</figure>
			)}
		</div>
	);
}
