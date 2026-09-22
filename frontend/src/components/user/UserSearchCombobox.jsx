import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Search, Loader2, X, User, Check, Shield } from 'lucide-react';
import { searchUsers } from '../../services/userService';
import { ROLE_LABELS } from '../../constants/roles';
import './UserSearchCombobox.css';

export default function UserSearchCombobox({
  onSelect,
  excludeUserIds = [],
  placeholder = 'Tìm người dùng theo tên, mã hoặc email...',
  disabled = false,
  selectedUser = null,
  onClear = null,
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [isOpen, setIsOpen] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);

  const containerRef = useRef(null);
  const debounceTimerRef = useRef(null);

  // Close dropdown on outside click
  useEffect(() => {
    const handleOutsideClick = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleOutsideClick);
    return () => document.removeEventListener('mousedown', handleOutsideClick);
  }, []);

  const performSearch = useCallback(async (keyword) => {
    const clean = keyword.trim();
    if (clean.length < 2) {
      setResults([]);
      setLoading(false);
      setHasSearched(false);
      return;
    }

    setLoading(true);
    setHasSearched(true);
    try {
      const data = await searchUsers(clean, 0, 20);
      const items = data?.content || [];
      // Filter out excluded user IDs if specified
      const filtered = excludeUserIds.length > 0
        ? items.filter((u) => !excludeUserIds.includes(u.id))
        : items;
      setResults(filtered);
    } catch (err) {
      console.error('Lỗi tìm kiếm người dùng:', err);
      setResults([]);
    } finally {
      setLoading(false);
    }
  }, [excludeUserIds]);

  const handleInputChange = (e) => {
    const val = e.target.value;
    setQuery(val);
    setIsOpen(true);

    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }

    if (val.trim().length >= 2) {
      setLoading(true);
      debounceTimerRef.current = setTimeout(() => {
        performSearch(val);
      }, 300);
    } else {
      setResults([]);
      setLoading(false);
      setHasSearched(false);
    }
  };

  const handleSelectUser = (user) => {
    onSelect?.(user);
    setIsOpen(false);
    setQuery('');
  };

  const handleClearSelection = (e) => {
    e.stopPropagation();
    onClear?.();
    setQuery('');
    setResults([]);
  };

  return (
    <div className="user-combobox" ref={containerRef}>
      {selectedUser ? (
        <div className="user-combobox__selected">
          <div className="user-combobox__selected-info">
            <span className="user-combobox__selected-name">{selectedUser.fullName}</span>
            <span className="user-combobox__selected-code">({selectedUser.userCode})</span>
            <span className={`user-combobox__role-pill role--${selectedUser.role}`}>
              {ROLE_LABELS[selectedUser.role] || selectedUser.role}
            </span>
            <span className="user-combobox__level-pill">
              Level {selectedUser.accessLevel ?? 1}
            </span>
          </div>
          {!disabled && onClear && (
            <button
              type="button"
              className="user-combobox__clear-btn"
              onClick={handleClearSelection}
              title="Bỏ chọn người dùng"
            >
              <X size={14} />
            </button>
          )}
        </div>
      ) : (
        <div className="user-combobox__input-wrap">
          <Search size={15} className="user-combobox__search-icon" />
          <input
            type="text"
            className="user-combobox__input"
            value={query}
            onChange={handleInputChange}
            onFocus={() => {
              if (query.trim().length >= 2) setIsOpen(true);
            }}
            placeholder={placeholder}
            disabled={disabled}
          />
          {loading && (
            <Loader2 size={15} className="user-combobox__spinner animate-spin" />
          )}
          {!loading && query && (
            <button
              type="button"
              className="user-combobox__input-clear"
              onClick={() => {
                setQuery('');
                setResults([]);
                setIsOpen(false);
              }}
            >
              <X size={14} />
            </button>
          )}
        </div>
      )}

      {/* Dropdown Results */}
      {!selectedUser && isOpen && (
        <div className="user-combobox__dropdown">
          {query.trim().length < 2 ? (
            <div className="user-combobox__hint">
              Nhập tối thiểu 2 ký tự để tìm kiếm người dùng...
            </div>
          ) : loading ? (
            <div className="user-combobox__loading">
              <Loader2 size={16} className="animate-spin" />
              <span>Đang tìm kiếm...</span>
            </div>
          ) : results.length === 0 ? (
            <div className="user-combobox__empty">
              {hasSearched ? 'Không tìm thấy người dùng' : 'Nhập từ khóa để tìm'}
            </div>
          ) : (
            <ul className="user-combobox__list">
              {results.map((u) => (
                <li
                  key={u.id}
                  className="user-combobox__item"
                  onClick={() => handleSelectUser(u)}
                >
                  <div className="user-combobox__item-main">
                    <span className="user-combobox__item-name">{u.fullName}</span>
                    <span className="user-combobox__item-code">{u.userCode}</span>
                  </div>
                  <div className="user-combobox__item-badges">
                    <span className={`user-combobox__role-pill role--${u.role}`}>
                      {ROLE_LABELS[u.role] || u.role}
                    </span>
                    <span className="user-combobox__level-pill">
                      Level {u.accessLevel ?? 1}
                    </span>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
