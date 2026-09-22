import React, { useState, useCallback, useRef } from 'react';
import {
  Search,
  Loader2,
  Info,
  X,
  Users
} from 'lucide-react';
import { toast } from 'sonner';
import { useAuth } from '../../context/AuthContext';
import { searchUsers, updateUserAccessLevel } from '../../services/userService';
import { ROLE_LABELS } from '../../constants/roles';
import Button from '../../components/ui/Button';
import './UserAccessLevelPage.css';

const ACCESS_LEVELS = [
  { level: 1, name: 'Cấp 1 — Mọi người dùng' },
  { level: 2, name: 'Cấp 2 — Nhân viên' },
  { level: 3, name: 'Cấp 3 — Cấp cao' },
];

export default function UserAccessLevelPage() {
  const { user: currentUser } = useAuth();

  const [keyword, setKeyword] = useState('');
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [selectedLevels, setSelectedLevels] = useState({}); // { [userId]: newLevel }
  const [savingId, setSavingId] = useState(null);

  const debounceRef = useRef(null);

  const handleSearch = useCallback(async (q) => {
    const clean = q.trim();
    if (clean.length < 2) {
      setUsers([]);
      setLoading(false);
      setHasSearched(false);
      return;
    }

    setLoading(true);
    setHasSearched(true);
    try {
      const res = await searchUsers(clean, 0, 20);
      const items = res?.content || [];
      setUsers(items);

      // Initialize selectedLevels map with each user's current level
      const initialMap = {};
      items.forEach((u) => {
        initialMap[u.id] = u.accessLevel ?? 1;
      });
      setSelectedLevels(initialMap);
    } catch (err) {
      console.error('Lỗi tìm kiếm người dùng:', err);
      toast.error(err?.message || 'Không thể tìm kiếm người dùng');
      setUsers([]);
    } finally {
      setLoading(false);
    }
  }, []);

  const handleKeywordChange = (e) => {
    const val = e.target.value;
    setKeyword(val);

    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }

    if (val.trim().length >= 2) {
      setLoading(true);
      debounceRef.current = setTimeout(() => {
        handleSearch(val);
      }, 300);
    } else {
      setUsers([]);
      setLoading(false);
      setHasSearched(false);
    }
  };

  const handleLevelChange = (userId, newLevel) => {
    setSelectedLevels((prev) => ({
      ...prev,
      [userId]: Number(newLevel),
    }));
  };

  const handleSaveLevel = async (targetUser) => {
    const newLevel = selectedLevels[targetUser.id];
    if (newLevel === undefined || newLevel === targetUser.accessLevel) {
      return;
    }

    setSavingId(targetUser.id);
    try {
      const updated = await updateUserAccessLevel(targetUser.id, newLevel);
      toast.success(
        `Đã cập nhật cấp độ truy cập của ${targetUser.fullName} thành Cấp ${updated.accessLevel}`
      );

      // Update local state
      setUsers((prev) =>
        prev.map((u) => (u.id === targetUser.id ? { ...u, accessLevel: updated.accessLevel } : u))
      );
    } catch (err) {
      console.error('Lỗi cập nhật cấp độ truy cập:', err);
      toast.error(err?.message || 'Không thể cập nhật cấp độ truy cập');
    } finally {
      setSavingId(null);
    }
  };

  return (
    <div className="access-level-page">
      {/* Header */}
      <div className="access-level-page__header">
        <div>
          <h1 className="access-level-page__title">Phân quyền người dùng (User Access Level)</h1>
          <p className="access-level-page__subtitle">
            Tra cứu và điều chỉnh cấp độ truy cập của người dùng trong khuôn viên nhà trường
          </p>
        </div>
      </div>

      {/* Explanatory Callout Banner */}
      <div className="access-level-callout">
        <div className="access-level-callout__icon">
          <Info size={20} />
        </div>
        <div className="access-level-callout__content">
          <div className="access-level-callout__title">Quy tắc phân cấp độ truy cập:</div>
          <div className="access-level-callout__text">
            Người dùng có <strong>Cấp độ truy cập (User Access Level)</strong> lớn hơn hoặc bằng{' '}
            <strong>Cấp độ khu vực (Area Access Level)</strong> sẽ được <strong>vào tự do</strong> tại các khu vực không bật cờ <em>"Chỉ định đích danh"</em>.
          </div>
          <div className="access-level-callout__tiers">
            <span className="tier-tag tier-tag--1">
              <strong>Cấp 1:</strong> Mọi người dùng (Mặc định: Sinh viên, Giảng viên, Khách)
            </span>
            <span className="tier-tag tier-tag--2">
              <strong>Cấp 2:</strong> Nhân viên (Mặc định: Bảo vệ, Quản lý cơ sở)
            </span>
            <span className="tier-tag tier-tag--3">
              <strong>Cấp 3:</strong> Cấp cao (Khu vực đặc thù nhạy cảm, phòng máy chủ)
            </span>
          </div>
        </div>
      </div>

      {/* Search Toolbar */}
      <div className="access-level-toolbar">
        <div className="access-level-search-box">
          <Search size={16} className="access-level-search-box__icon" />
          <input
            type="text"
            className="access-level-search-box__input"
            placeholder="Tìm kiếm người dùng theo họ tên hoặc mã số (tối thiểu 2 ký tự)..."
            value={keyword}
            onChange={handleKeywordChange}
          />
          {keyword && (
            <button
              type="button"
              className="access-level-search-box__clear"
              onClick={() => {
                setKeyword('');
                setUsers([]);
                setHasSearched(false);
              }}
              title="Xoá từ khoá"
            >
              <X size={14} />
            </button>
          )}
        </div>
      </div>

      {/* Table Container */}
      <div className="access-level-table-card">
        {loading ? (
          <div className="access-level-empty">
            <Loader2 size={28} className="animate-spin" />
            <p>Đang tìm kiếm người dùng...</p>
          </div>
        ) : !hasSearched ? (
          <div className="access-level-empty">
            <Search size={32} />
            <p className="access-level-empty__title">Tra cứu người dùng để điều chỉnh cấp độ</p>
            <span className="access-level-empty__desc">
              Nhập tối thiểu 2 ký tự vào ô tìm kiếm bên trên để xem kết quả.
            </span>
          </div>
        ) : users.length === 0 ? (
          <div className="access-level-empty">
            <Users size={32} />
            <p className="access-level-empty__title">Không tìm thấy người dùng phù hợp</p>
            <span className="access-level-empty__desc">
              Vui lòng kiểm tra lại từ khóa tìm kiếm (tên hoặc mã số người dùng).
            </span>
          </div>
        ) : (
          <div className="access-level-table-wrapper">
            <table className="access-level-table">
              <thead>
                <tr>
                  <th>Mã định danh</th>
                  <th>Họ và tên</th>
                  <th>Vai trò</th>
                  <th>Cấp độ hiện tại</th>
                  <th>Cấp độ mới</th>
                  <th style={{ textAlign: 'center' }}>Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {users.map((item) => {
                  const isSelf =
                    (currentUser?.id && item.id === currentUser.id) ||
                    (currentUser?.email && item.email?.toLowerCase() === currentUser.email?.toLowerCase());

                  const currentLevel = item.accessLevel ?? 1;
                  const selectedLevel = selectedLevels[item.id] ?? currentLevel;
                  const isDirty = selectedLevel !== currentLevel;
                  const isSaving = savingId === item.id;

                  return (
                    <tr key={item.id} className={isSelf ? 'access-level-row--self' : ''}>
                      <td className="access-level-cell--code">{item.userCode}</td>
                      <td className="access-level-cell--name">
                        <div className="user-name-wrapper">
                          <span>{item.fullName}</span>
                          {isSelf && (
                            <span className="user-self-badge" title="Tài khoản đang đăng nhập">
                              Bạn
                            </span>
                          )}
                        </div>
                      </td>
                      <td>
                        <span className={`access-level-role-pill role--${item.role}`}>
                          {ROLE_LABELS[item.role] || item.role}
                        </span>
                      </td>
                      <td>
                        <span className={`access-level-pill level--${currentLevel}`}>
                          Level {currentLevel}
                        </span>
                      </td>
                      <td>
                        <div className="access-level-select-wrap">
                          <select
                            className="access-level-select"
                            value={selectedLevel}
                            onChange={(e) => handleLevelChange(item.id, e.target.value)}
                            disabled={isSelf || isSaving}
                            title={
                              isSelf
                                ? 'Bạn không thể tự thay đổi cấp truy cập của chính mình'
                                : undefined
                            }
                          >
                            {ACCESS_LEVELS.map((opt) => (
                              <option key={opt.level} value={opt.level}>
                                {opt.name}
                              </option>
                            ))}
                          </select>
                        </div>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        {isSelf ? (
                          <span
                            className="access-level-self-hint"
                            title="Không thể tự thay đổi cấp truy cập của chính mình"
                          >
                            Không khả dụng
                          </span>
                        ) : (
                          <Button
                            variant="primary"
                            size="sm"
                            onClick={() => handleSaveLevel(item)}
                            disabled={!isDirty || isSaving}
                            loading={isSaving}
                          >
                            Lưu
                          </Button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
