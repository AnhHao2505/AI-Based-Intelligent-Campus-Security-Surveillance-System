import React, { useState } from "react";
import { Eye, EyeOff } from "lucide-react";
import Button from "../../components/ui/Button";
import guestVisitService from "../../services/guestVisitService";
import { guestHasPhoto } from "../../utils/guestHelpers";

/**
 * Xem ảnh khách ngay trong bảng chi tiết lượt (B1, B3): chỉ xin URL có hạn khi người dùng bấm "Xem ảnh",
 * mỗi lần xin backend ghi audit VIEW_PHOTO. Bấm lại để ẩn. Khách không còn ảnh thì không hiện gì.
 */
export default function GuestPhotoThumb({ visitId, guest }) {
	const [url, setUrl] = useState(null);
	const [loading, setLoading] = useState(false);
	const [error, setError] = useState(null);

	if (!guestHasPhoto(guest)) return null;

	const toggle = async () => {
		if (url) {
			setUrl(null);
			return;
		}
		setLoading(true);
		setError(null);
		try {
			const res = await guestVisitService.getGuestPhotoUrl(visitId, guest.id);
			if (res?.url) {
				setUrl(res.url);
			} else {
				setError("Không lấy được đường dẫn xem ảnh.");
			}
		} catch (err) {
			setError(err?.message || "Không tải được ảnh của khách.");
		} finally {
			setLoading(false);
		}
	};

	return (
		<div className="guest-visit__photo-view">
			<Button variant="ghost" size="sm" icon={url ? EyeOff : Eye} onClick={toggle} loading={loading}>
				{url ? "Ẩn ảnh" : "Xem ảnh"}
			</Button>
			{url && <img className="guest-visit__photo-img" src={url} alt={`Ảnh của khách ${guest.fullName}`} />}
			{error && (
				<span className="guest-visit__hint guest-visit__hint--error" role="alert">
					{error}
				</span>
			)}
		</div>
	);
}
