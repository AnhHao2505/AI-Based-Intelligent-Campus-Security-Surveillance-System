import { useCallback, useEffect, useId, useRef, useState } from "react";
import accessRequestService from "../../services/accessRequestService";

const DEBOUNCE_MS = 250;
// Chỉ tra gợi ý khi ô chứa MỘT mã (khớp quy tắc backend); danh sách nhiều mã (dấu phẩy/khoảng trắng) giữ luồng cũ
const SINGLE_CODE = /^[A-Za-z0-9-]{1,20}$/;

/**
 * Ô nhập mã thành viên nhóm có gợi ý (autocomplete).
 * - Ô trống + ↓: thành viên gần đây (đơn nhóm trước đây của chính người dùng).
 * - Gõ mã: gợi ý mã bắt đầu bằng chuỗi đã gõ (debounce, có cache theo chuỗi để tiết kiệm rate limit).
 * - ↑/↓ chọn dòng, Enter chọn dòng đang sáng (không có dòng sáng -> onSubmitTyped như cũ), Esc/click ngoài đóng.
 * - Chọn một dòng gọi onPick(code): trang cha đi qua đúng luồng thêm thành viên (resolve-members).
 * - Lỗi mạng / 429: đóng danh sách, ô vẫn dùng kiểu cũ (gõ + Enter).
 */
export default function MemberCodeCombobox({
	value,
	onChange,
	onPick,
	onSubmitTyped,
	excludeCodes = [],
	disabled = false,
	placeholder,
}) {
	const [open, setOpen] = useState(false);
	const [items, setItems] = useState([]);
	const [loading, setLoading] = useState(false);
	const [activeIndex, setActiveIndex] = useState(-1);

	const listId = useId();
	const containerRef = useRef(null);
	const debounceRef = useRef(null);
	const requestSeqRef = useRef(0);
	const cacheRef = useRef(new Map());

	const excluded = new Set(excludeCodes.filter(Boolean).map((c) => String(c).toUpperCase()));
	const visibleItems = items.filter((it) => !excluded.has(String(it.userCode).toUpperCase()));

	const closeList = useCallback(() => {
		setOpen(false);
		setActiveIndex(-1);
	}, []);

	// Chỉ dùng ref và setter -> ổn định giữa các lần render
	const fetchSuggestions = useCallback(async (query) => {
		const key = query || "";
		const seq = ++requestSeqRef.current;
		if (cacheRef.current.has(key)) {
			setItems(cacheRef.current.get(key));
			setLoading(false);
			setOpen(true);
			setActiveIndex(-1);
			return;
		}
		setLoading(true);
		setOpen(true);
		try {
			const data = await accessRequestService.getMemberSuggestions(query || undefined);
			if (seq !== requestSeqRef.current) return; // đã có lần gõ mới hơn
			const list = Array.isArray(data) ? data : [];
			cacheRef.current.set(key, list);
			setItems(list);
			setActiveIndex(-1);
		} catch {
			if (seq !== requestSeqRef.current) return;
			setItems([]);
			closeList();
		} finally {
			if (seq === requestSeqRef.current) setLoading(false);
		}
	}, [closeList]);

	// Gõ -> debounce gợi ý theo tiền tố
	useEffect(() => {
		if (debounceRef.current) clearTimeout(debounceRef.current);
		const query = (value || "").trim();
		if (!query || !SINGLE_CODE.test(query)) {
			if (query) closeList();
			return undefined;
		}
		debounceRef.current = setTimeout(() => fetchSuggestions(query), DEBOUNCE_MS);
		return () => clearTimeout(debounceRef.current);
	}, [value, fetchSuggestions, closeList]);

	// Click ra ngoài -> đóng, giữ nguyên chữ đã gõ
	useEffect(() => {
		const handleOutside = (e) => {
			if (containerRef.current && !containerRef.current.contains(e.target)) closeList();
		};
		document.addEventListener("mousedown", handleOutside);
		return () => document.removeEventListener("mousedown", handleOutside);
	}, [closeList]);

	const pick = (item) => {
		closeList();
		onPick?.(item.userCode);
	};

	const handleKeyDown = (e) => {
		if (e.key === "ArrowDown") {
			e.preventDefault();
			if (!open) {
				const query = (value || "").trim();
				if (!query) fetchSuggestions("");
				else if (SINGLE_CODE.test(query)) fetchSuggestions(query);
				return;
			}
			if (visibleItems.length > 0) {
				setActiveIndex((i) => (i + 1) % visibleItems.length);
			}
		} else if (e.key === "ArrowUp") {
			if (open && visibleItems.length > 0) {
				e.preventDefault();
				setActiveIndex((i) => (i <= 0 ? visibleItems.length - 1 : i - 1));
			}
		} else if (e.key === "Enter") {
			e.preventDefault();
			if (open && activeIndex >= 0 && visibleItems[activeIndex]) {
				pick(visibleItems[activeIndex]);
			} else {
				closeList();
				onSubmitTyped?.();
			}
		} else if (e.key === "Escape") {
			if (open) {
				e.preventDefault();
				closeList();
			}
		}
	};

	const optionId = (index) => `${listId}-opt-${index}`;
	const showList = open && !disabled;

	return (
		<div className="arp-member-combobox" ref={containerRef}>
			<input
				type="text"
				className="arp-input"
				role="combobox"
				aria-autocomplete="list"
				aria-expanded={showList}
				aria-controls={listId}
				aria-activedescendant={showList && activeIndex >= 0 ? optionId(activeIndex) : undefined}
				placeholder={placeholder}
				value={value}
				onChange={(e) => onChange(e.target.value)}
				onKeyDown={handleKeyDown}
				disabled={disabled}
				autoComplete="off"
			/>
			{showList && (
				<ul id={listId} role="listbox" className="arp-member-combobox__list">
					{loading && visibleItems.length === 0 ? (
						<li className="arp-member-combobox__empty" role="presentation">
							Đang tìm…
						</li>
					) : visibleItems.length === 0 ? (
						<li className="arp-member-combobox__empty" role="presentation">
							Không tìm thấy mã phù hợp
						</li>
					) : (
						visibleItems.map((item, index) => (
							<li
								key={item.userCode}
								id={optionId(index)}
								role="option"
								aria-selected={index === activeIndex}
								className={`arp-member-combobox__option ${index === activeIndex ? "arp-member-combobox__option--active" : ""}`}
								onMouseDown={(e) => e.preventDefault()}
								onMouseEnter={() => setActiveIndex(index)}
								onClick={() => pick(item)}
							>
								<span className="arp-member-combobox__code">{item.userCode}</span>
								<span className="arp-member-combobox__name"> — {item.fullName}</span>
							</li>
						))
					)}
				</ul>
			)}
		</div>
	);
}
