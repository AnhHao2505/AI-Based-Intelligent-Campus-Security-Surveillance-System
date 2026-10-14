import { Badge } from "../ui";
import { ACCESS_RESULT_META } from "../../utils/accessHistoryHelpers";

/** Badge kết quả ra vào (5 nhóm) — màu theo token qua Badge, nhãn/icon từ ACCESS_RESULT_META. */
export default function AccessResultBadge({ result }) {
	const meta = ACCESS_RESULT_META[result];
	if (!meta) {
		return <Badge variant="neutral">{result || "—"}</Badge>;
	}
	return (
		<Badge variant={meta.badge} icon={meta.icon}>
			{meta.label}
		</Badge>
	);
}
