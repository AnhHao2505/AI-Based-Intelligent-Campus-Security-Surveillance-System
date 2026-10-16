import { Navigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import { ROLES } from "../../constants/roles";

// Link cũ /admin/access-levels: FM về trang cấp truy cập người dùng, ADMIN về mặc định theo loại khu vực
export default function AccessLevelsRedirect() {
	const { user } = useAuth();
	const target = user?.role === ROLES.FACILITY_MANAGER ? "/fm/access-levels" : "/admin/level-presets";
	return <Navigate to={target} replace />;
}
