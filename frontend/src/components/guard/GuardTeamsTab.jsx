import React, { useState, useEffect, useMemo } from 'react';
import {
  Users,
  Plus,
  Edit2,
  Trash2,
  Check,
  X,
  Search,
  AlertCircle,
  Shield,
  UserCheck,
  UserX,
  Palette,
  FileText,
  Sun,
  Sunset,
  Moon,
  Zap,
  CheckCircle2
} from 'lucide-react';
import { guardScheduleApi } from '../../api/guardScheduleApi';
import { getUsers } from '../../services/userService';
import { ROLES } from '../../constants/roles';

const PRESET_COLORS = [
  '#2563eb', // Blue
  '#0d9488', // Teal
  '#16a34a', // Green
  '#d97706', // Amber
  '#dc2626', // Red
  '#7c3aed', // Purple
  '#4f46e5', // Indigo
  '#0891b2'  // Cyan
];
// Helper sắp xếp theo mã bảo vệ (userCode) theo thứ tự tự nhiên (ví dụ BV1, BV2, BV10...)
const compareUserCodes = (a, b) => {
  const codeA = (a.userCode || '').trim();
  const codeB = (b.userCode || '').trim();
  if (codeA && codeB) {
    const cmp = codeA.localeCompare(codeB, undefined, { numeric: true, sensitivity: 'base' });
    if (cmp !== 0) return cmp;
  } else if (codeA) {
    return -1;
  } else if (codeB) {
    return 1;
  }
  return (a.fullName || '').localeCompare(b.fullName || '', 'vi', { sensitivity: 'base' });
};

export default function GuardTeamsTab({
  teams: propTeams = [],
  allGuards = [],
  areas = [],
  onTeamsUpdated,
  isCreateModalOpen,
  setIsCreateModalOpen
}) {
  const [teams, setTeams] = useState(propTeams);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Danh sách bảo vệ nội bộ: đồng bộ từ allGuards hoặc tự fetch nếu prop rỗng
  const [internalGuards, setInternalGuards] = useState(allGuards);
  useEffect(() => {
    if (Array.isArray(allGuards) && allGuards.length > 0) {
      setInternalGuards(allGuards);
    } else {
      getUsers({ accountType: 'SYSTEM', isActive: true, size: 100 })
        .then((res) => {
          const pageObj = res?.users || res;
          const list = Array.isArray(pageObj?.content)
            ? pageObj.content
            : Array.isArray(res?.content)
            ? res.content
            : Array.isArray(res)
            ? res
            : [];
          const guardOnly = list.filter(
            (u) =>
              u.isActive !== false &&
              (u.role === 'GUARD' ||
               u.role === ROLES.GUARD ||
               u.role === 'INTERNAL_GUARD' ||
               u.role === 'OUTSOURCED_GUARD')
          );
          if (guardOnly.length > 0) {
            setInternalGuards(guardOnly);
          }
        })
        .catch((e) => console.warn('Lỗi tải danh sách bảo vệ trong GuardTeamsTab:', e));
    }
  }, [allGuards]);

  // Tạo map từ memberId -> thông tin team
  const guardTeamMap = useMemo(() => {
    const map = new Map();
    (teams || []).forEach((t) => {
      (t.members || []).forEach((m) => {
        map.set(m.id, { id: t.id, teamName: t.teamName, colorCode: t.colorCode });
      });
    });
    return map;
  }, [teams]);

  // Danh sách bảo vệ đã được liên kết thông tin đội (sắp xếp theo mã bảo vệ)
  const linkedGuards = useMemo(() => {
    return (internalGuards || []).map((g) => {
      const teamInfo = guardTeamMap.get(g.id);
      const activeTeam = teamInfo || (g.teamId && teams.find((t) => t.id === g.teamId)) || null;
      const teamId = activeTeam?.id || null;
      const teamName = activeTeam?.teamName || null;
      return {
        ...g,
        teamId,
        teamName,
        team: activeTeam ? { id: teamId, teamName, colorCode: activeTeam.colorCode } : null
      };
    }).sort(compareUserCodes);
  }, [internalGuards, guardTeamMap, teams]);

  // Synchronize with parent teams
  useEffect(() => {
    if (Array.isArray(propTeams) && propTeams.length > 0) {
      setTeams(propTeams);
    }
  }, [propTeams]);

  // Search & filter
  const [searchKeyword, setSearchKeyword] = useState('');

  // Selected team for member management dialog
  const [assigningTeam, setAssigningTeam] = useState(null);
  const [assignModalTab, setAssignModalTab] = useState('PERMANENT'); // 'PERMANENT' | 'DISPATCH'
  const [selectedGuardIds, setSelectedGuardIds] = useState([]);
  const [memberSearch, setMemberSearch] = useState('');
  const [savingMembers, setSavingMembers] = useState(false);

  // Temporary Dispatch Form State
  const todayDateStr = useMemo(() => {
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, '0');
    const d = String(now.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }, []);
  const [dispatchStartDate, setDispatchStartDate] = useState(todayDateStr);
  const [dispatchEndDate, setDispatchEndDate] = useState(todayDateStr);
  const [dispatchShiftType, setDispatchShiftType] = useState('ALL');
  const [dispatchReason, setDispatchReason] = useState('');
  const [selectedDispatchGuardIds, setSelectedDispatchGuardIds] = useState([]);
  const [dispatchSearch, setDispatchSearch] = useState('');
  const [savingDispatch, setSavingDispatch] = useState(false);
  const [dispatches, setDispatches] = useState([]);
  const [availableDispatchGuards, setAvailableDispatchGuards] = useState([]);
  const [loadingAvailableGuards, setLoadingAvailableGuards] = useState(false);

  // Edit / Create team modal
  const [editingTeam, setEditingTeam] = useState(null);
  const [teamForm, setTeamForm] = useState({
    teamName: '',
    description: '',
    colorCode: '#2563eb'
  });
  const [submittingTeam, setSubmittingTeam] = useState(false);

  // Shift Demand Configuration (Khi tạo đội mới)
  // 1. Ngày học & làm việc (Thứ 2 - Thứ 7 • 6 ngày)
  const [weekdayMorningDemand, setWeekdayMorningDemand] = useState(3);
  const [weekdayAfternoonDemand, setWeekdayAfternoonDemand] = useState(4);
  const [weekdayNightDemand, setWeekdayNightDemand] = useState(2);

  // 2. Chủ Nhật (1 ngày) - Cố định hiển thị, tối thiểu 2 người/ca (1 camera + 1 tuần tra)
  const [sundayMorningDemand, setSundayMorningDemand] = useState(2);
  const [sundayAfternoonDemand, setSundayAfternoonDemand] = useState(2);
  const [sundayNightDemand, setSundayNightDemand] = useState(2);

  const [newTeamMemberIds, setNewTeamMemberIds] = useState([]);
  const [createMemberSearch, setCreateMemberSearch] = useState('');

  // Nhu cầu và đề xuất quân số tự động
  const m_wd = Math.max(0, parseInt(weekdayMorningDemand, 10) || 0);
  const a_wd = Math.max(0, parseInt(weekdayAfternoonDemand, 10) || 0);
  const n_wd = Math.max(0, parseInt(weekdayNightDemand, 10) || 0);
  const weekdayDailyDemand = m_wd + a_wd + n_wd;

  const m_su = Math.max(0, parseInt(sundayMorningDemand, 10) || 0);
  const a_su = Math.max(0, parseInt(sundayAfternoonDemand, 10) || 0);
  const n_su = Math.max(0, parseInt(sundayNightDemand, 10) || 0);
  const sundayDailyDemand = m_su + a_su + n_su;

  const weeklyDemand = (weekdayDailyDemand * 6) + (sundayDailyDemand * 1);

  const standardQuota = 6;
  const minGuardsRecommended = weeklyDemand > 0 ? Math.ceil(weeklyDemand / standardQuota) : 0;
  const safeGuardsRecommended = minGuardsRecommended > 0 ? minGuardsRecommended + Math.max(1, Math.ceil(minGuardsRecommended * 0.15)) : 0;

  // Fetch teams and active dispatches from backend
  const fetchTeams = async () => {
    setLoading(true);
    setError(null);
    try {
      const [teamsRes, dispatchesRes] = await Promise.allSettled([
        guardScheduleApi.getTeams(),
        guardScheduleApi.getDispatches()
      ]);
      const list = teamsRes.status === 'fulfilled' && Array.isArray(teamsRes.value) ? teamsRes.value : [];
      setTeams(list);
      if (dispatchesRes.status === 'fulfilled' && Array.isArray(dispatchesRes.value)) {
        setDispatches(dispatchesRes.value);
      }
      if (onTeamsUpdated) onTeamsUpdated();
    } catch (err) {
      console.error('Lỗi tải danh sách đội bảo vệ:', err);
      setError(err.message || 'Không thể tải danh sách đội bảo vệ');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTeams();
  }, []);

  // Filter teams by search keyword
  const filteredTeams = useMemo(() => {
    if (!searchKeyword.trim()) return teams;
    const kw = searchKeyword.toLowerCase().trim();
    return teams.filter(
      (t) =>
        (t.teamName && t.teamName.toLowerCase().includes(kw)) ||
        (t.description && t.description.toLowerCase().includes(kw))
    );
  }, [teams, searchKeyword]);

  // Total assigned guards count
  const totalAssignedGuards = useMemo(() => {
    return linkedGuards.filter((g) => !!g.teamId).length;
  }, [linkedGuards]);

  // Lọc danh sách bảo vệ khi tạo đội mới (ưu tiên đã chọn, chưa phân đội lên đầu, trong cùng nhóm xếp theo mã bảo vệ)
  const filteredGuardsForCreate = useMemo(() => {
    let list = linkedGuards;
    if (createMemberSearch.trim()) {
      const kw = createMemberSearch.toLowerCase().trim();
      list = list.filter(
        (g) =>
          (g.fullName && g.fullName.toLowerCase().includes(kw)) ||
          (g.userCode && g.userCode.toLowerCase().includes(kw)) ||
          (g.email && g.email.toLowerCase().includes(kw))
      );
    }
    return [...list].sort((a, b) => {
      // 1. Đã chọn lên đầu
      const aChecked = newTeamMemberIds.includes(a.id) ? 0 : 1;
      const bChecked = newTeamMemberIds.includes(b.id) ? 0 : 1;
      if (aChecked !== bChecked) return aChecked - bChecked;

      // 2. Chưa phân đội lên trước người đã thuộc đội khác
      const aAssigned = a.team?.id || a.teamId ? 1 : 0;
      const bAssigned = b.team?.id || b.teamId ? 1 : 0;
      if (aAssigned !== bAssigned) return aAssigned - bAssigned;

      // 3. Trong cùng nhóm: Xếp theo mã bảo vệ
      return compareUserCodes(a, b);
    });
  }, [linkedGuards, createMemberSearch, newTeamMemberIds]);

  // Chọn nhanh bảo vệ chưa phân đội đủ số lượng đề xuất (theo mã bảo vệ)
  const handleQuickSelectUnassigned = () => {
    const unassigned = linkedGuards
      .filter((g) => !g.team?.id && !g.teamId)
      .sort(compareUserCodes);
    const countNeeded = minGuardsRecommended || 10;
    const chosen = unassigned.slice(0, countNeeded).map((g) => g.id);
    setNewTeamMemberIds(chosen);
  };

  // Toggle guard in Create Team modal
  const handleToggleCreateMember = (guardId) => {
    setNewTeamMemberIds((prev) =>
      prev.includes(guardId) ? prev.filter((id) => id !== guardId) : [...prev, guardId]
    );
  };

  // Đóng modal và reset trạng thái
  const handleCloseModal = () => {
    if (setIsCreateModalOpen) setIsCreateModalOpen(false);
    setEditingTeam(null);
    setNewTeamMemberIds([]);
    setCreateMemberSearch('');
  };

  // Tự động khởi tạo dữ liệu đề xuất chuẩn khi mở modal tạo đội từ bên ngoài
  useEffect(() => {
    if (isCreateModalOpen && !editingTeam) {
      setWeekdayMorningDemand(3);
      setWeekdayAfternoonDemand(4);
      setWeekdayNightDemand(2);
      setSundayMorningDemand(2);
      setSundayAfternoonDemand(2);
      setSundayNightDemand(2);
      setCreateMemberSearch('');
      const unassigned = linkedGuards
        .filter((g) => !g.team?.id && !g.teamId)
        .sort(compareUserCodes);
      // Đề xuất 10 bảo vệ chuẩn cho tuần (54 ca T2-T7 + 6 ca CN = 60 ca, 6 ca/người)
      setNewTeamMemberIds(unassigned.slice(0, 10).map((g) => g.id));
      setTeamForm({
        teamName: '',
        description: '',
        colorCode: PRESET_COLORS[teams.length % PRESET_COLORS.length] || '#2563eb'
      });
    }
  }, [isCreateModalOpen, editingTeam, linkedGuards, teams.length]);

  // Open Create Modal
  const handleOpenCreate = () => {
    setEditingTeam(null);
    setWeekdayMorningDemand(3);
    setWeekdayAfternoonDemand(4);
    setWeekdayNightDemand(2);
    setSundayMorningDemand(2);
    setSundayAfternoonDemand(2);
    setSundayNightDemand(2);
    setCreateMemberSearch('');
    const unassigned = linkedGuards
      .filter((g) => !g.team?.id && !g.teamId)
      .sort(compareUserCodes);
    setNewTeamMemberIds(unassigned.slice(0, 10).map((g) => g.id));
    setTeamForm({
      teamName: '',
      description: '',
      colorCode: PRESET_COLORS[teams.length % PRESET_COLORS.length] || '#2563eb'
    });
    if (setIsCreateModalOpen) {
      setIsCreateModalOpen(true);
    }
  };

  // Open Edit Modal
  const handleOpenEdit = (team) => {
    setEditingTeam(team);
    setTeamForm({
      teamName: team.teamName || '',
      description: team.description || '',
      colorCode: team.colorCode || '#2563eb'
    });
    if (setIsCreateModalOpen) {
      setIsCreateModalOpen(true);
    }
  };

  // Submit Create or Edit Team
  const handleSaveTeam = async (e) => {
    e.preventDefault();
    if (!teamForm.teamName.trim()) {
      alert('Vui lòng nhập tên đội bảo vệ');
      return;
    }

    setSubmittingTeam(true);
    try {
      if (editingTeam) {
        await guardScheduleApi.updateTeam(editingTeam.id, {
          teamName: teamForm.teamName.trim(),
          description: teamForm.description.trim(),
          colorCode: teamForm.colorCode
        });
      } else {
        const finalDescription = teamForm.description ? teamForm.description.trim() : '';

        const createdTeam = await guardScheduleApi.createTeam({
          teamName: teamForm.teamName.trim(),
          description: finalDescription,
          colorCode: teamForm.colorCode
        });

        // Tự động gán ngay quân số đã chọn cho đội vừa tạo
        if (createdTeam && createdTeam.id && newTeamMemberIds.length > 0) {
          await guardScheduleApi.assignTeamMembers(createdTeam.id, {
            guardIds: newTeamMemberIds
          });
        }
      }

      await fetchTeams();
      if (onTeamsUpdated) onTeamsUpdated();
      handleCloseModal();
    } catch (err) {
      alert(err.message || 'Lỗi khi lưu thông tin đội');
    } finally {
      setSubmittingTeam(false);
    }
  };

  // Delete team
  const handleDeleteTeam = async (team) => {
    if (!window.confirm(`Bạn có chắc chắn muốn xóa "${team.teamName}"? Các nhân viên trong đội sẽ chuyển về trạng thái Chưa phân đội.`)) {
      return;
    }
    setLoading(true);
    try {
      await guardScheduleApi.deleteTeam(team.id);
      await fetchTeams();
      if (onTeamsUpdated) onTeamsUpdated();
    } catch (err) {
      alert(err.message || 'Lỗi khi xóa đội');
    } finally {
      setLoading(false);
    }
  };

  // Open Member Assignment
  const handleOpenAssignMembers = (team) => {
    setAssigningTeam(team);
    setAssignModalTab('PERMANENT');
    // Find all guards currently in this team
    const currentMemberIds = (team.members && team.members.length > 0)
      ? team.members.map((m) => m.id)
      : linkedGuards
          .filter((g) => g.team?.id === team.id || g.teamId === team.id)
          .map((g) => g.id);
    setSelectedGuardIds(currentMemberIds);
    setMemberSearch('');
    setDispatchStartDate(todayDateStr);
    setDispatchEndDate(todayDateStr);
    setDispatchShiftType('ALL');
    setDispatchReason('');
    setSelectedDispatchGuardIds([]);
    setDispatchSearch('');
  };

  // Toggle member selection
  const handleToggleGuard = (guardId) => {
    setSelectedGuardIds((prev) =>
      prev.includes(guardId) ? prev.filter((id) => id !== guardId) : [...prev, guardId]
    );
  };

  // Toggle dispatch guard selection
  const handleToggleDispatchGuard = (guardId) => {
    setSelectedDispatchGuardIds((prev) =>
      prev.includes(guardId) ? prev.filter((id) => id !== guardId) : [...prev, guardId]
    );
  };

  // Save member assignment
  const handleSaveMembers = async () => {
    if (!assigningTeam) return;
    setSavingMembers(true);
    try {
      await guardScheduleApi.assignTeamMembers(assigningTeam.id, {
        guardIds: selectedGuardIds
      });
      await fetchTeams();
      if (onTeamsUpdated) onTeamsUpdated();
      setAssigningTeam(null);
    } catch (err) {
      alert(err.message || 'Lỗi khi gán thành viên cho đội');
    } finally {
      setSavingMembers(false);
    }
  };

  // Save Temporary Dispatch (tăng cường sự kiện)
  const handleSaveDispatch = async (e) => {
    if (e) e.preventDefault();
    if (!assigningTeam) return;
    if (selectedDispatchGuardIds.length === 0) {
      alert('Vui lòng chọn ít nhất một nhân viên bảo vệ để điều động tăng cường');
      return;
    }
    if (!dispatchStartDate || !dispatchEndDate) {
      alert('Vui lòng chọn ngày bắt đầu và kết thúc điều động');
      return;
    }
    if (dispatchEndDate < dispatchStartDate) {
      alert('Ngày kết thúc không được trước ngày bắt đầu');
      return;
    }

    setSavingDispatch(true);
    try {
      await guardScheduleApi.createDispatches({
        guardIds: selectedDispatchGuardIds,
        toTeamId: assigningTeam.id,
        startDate: dispatchStartDate,
        endDate: dispatchEndDate,
        shiftType: dispatchShiftType === 'ALL' ? null : dispatchShiftType,
        reason: dispatchReason.trim()
      });
      await fetchTeams();
      if (onTeamsUpdated) onTeamsUpdated();
      const shiftLabel = dispatchShiftType === 'SHIFT_MORNING' ? ' (Ca Sáng)' : dispatchShiftType === 'SHIFT_AFTERNOON' ? ' (Ca Chiều)' : dispatchShiftType === 'SHIFT_NIGHT' ? ' (Ca Đêm)' : '';
      alert(`Đã điều động thành công ${selectedDispatchGuardIds.length} bảo vệ tăng cường${shiftLabel} cho ${assigningTeam.teamName}!`);
      setSelectedDispatchGuardIds([]);
      setDispatchReason('');
      setDispatchShiftType('ALL');
      setAssigningTeam(null);
    } catch (err) {
      alert(err.message || 'Lỗi khi tạo đợt điều động tăng cường');
    } finally {
      setSavingDispatch(false);
    }
  };

  // Cancel Temporary Dispatch Early
  const handleCancelDispatch = async (dispatchId) => {
    if (!window.confirm('Bạn có chắc chắn muốn kết thúc sớm đợt điều động tăng cường này? Nhân viên sẽ hoàn trả về đội gốc ngay lập tức.')) {
      return;
    }
    try {
      await guardScheduleApi.cancelDispatch(dispatchId);
      await fetchTeams();
      if (onTeamsUpdated) onTeamsUpdated();
    } catch (err) {
      alert(err.message || 'Lỗi khi hủy đợt điều động');
    }
  };

  // Filter guards in assignment modal (ưu tiên đã chọn, thuộc đội/chưa phân đội lên đầu, trong cùng nhóm xếp theo mã bảo vệ)
  const filteredGuardsForAssignment = useMemo(() => {
    let list = linkedGuards;
    if (memberSearch.trim()) {
      const kw = memberSearch.toLowerCase().trim();
      list = list.filter(
        (g) =>
          (g.fullName && g.fullName.toLowerCase().includes(kw)) ||
          (g.userCode && g.userCode.toLowerCase().includes(kw)) ||
          (g.email && g.email.toLowerCase().includes(kw))
      );
    }
    return [...list].sort((a, b) => {
      // 1. Đã chọn lên đầu
      const aChecked = selectedGuardIds.includes(a.id) ? 0 : 1;
      const bChecked = selectedGuardIds.includes(b.id) ? 0 : 1;
      if (aChecked !== bChecked) return aChecked - bChecked;

      // 2. Thuộc đội hiện tại hoặc chưa phân đội lên trước người thuộc đội khác
      const aCurrentTeamId = a.teamId || a.team?.id;
      const bCurrentTeamId = b.teamId || b.team?.id;
      const aOther = aCurrentTeamId && assigningTeam?.id && aCurrentTeamId !== assigningTeam.id ? 1 : 0;
      const bOther = bCurrentTeamId && assigningTeam?.id && bCurrentTeamId !== assigningTeam.id ? 1 : 0;
      if (aOther !== bOther) return aOther - bOther;

      // 3. Trong cùng nhóm: Xếp theo mã bảo vệ
      return compareUserCodes(a, b);
    });
  }, [linkedGuards, memberSearch, selectedGuardIds, assigningTeam]);

  // Fetch available guards for dispatch (chỉ những người không trùng ca và không trực ca liên tiếp)
  useEffect(() => {
    if (assigningTeam && assignModalTab === 'DISPATCH' && dispatchStartDate && dispatchEndDate) {
      if (dispatchEndDate < dispatchStartDate) {
        setAvailableDispatchGuards([]);
        return;
      }
      let isMounted = true;
      setLoadingAvailableGuards(true);
      guardScheduleApi
        .getAvailableDispatchGuards({
          toTeamId: assigningTeam.id,
          startDate: dispatchStartDate,
          endDate: dispatchEndDate,
          shiftType: dispatchShiftType === 'ALL' ? undefined : dispatchShiftType
        })
        .then((res) => {
          if (isMounted) {
            const list = Array.isArray(res) ? res : [];
            setAvailableDispatchGuards(list);
            // Bỏ chọn những người không còn trong danh sách khả dụng
            setSelectedDispatchGuardIds((prev) =>
              prev.filter((id) => list.some((g) => g.id === id))
            );
          }
        })
        .catch((err) => {
          console.error('Lỗi khi tải danh sách bảo vệ khả dụng:', err);
          if (isMounted) setAvailableDispatchGuards([]);
        })
        .finally(() => {
          if (isMounted) setLoadingAvailableGuards(false);
        });

      return () => {
        isMounted = false;
      };
    } else {
      setAvailableDispatchGuards([]);
    }
  }, [assigningTeam, assignModalTab, dispatchStartDate, dispatchEndDate, dispatchShiftType]);

  // Filter guards available for dispatch (ưu tiên đã tick chọn, cùng đội > chưa phân đội > đội khác, trong cùng nhóm xếp theo mã bảo vệ)
  const filteredGuardsForDispatch = useMemo(() => {
    if (!assigningTeam || assignModalTab !== 'DISPATCH') return [];
    let list = availableDispatchGuards;

    if (dispatchSearch.trim()) {
      const kw = dispatchSearch.toLowerCase().trim();
      list = list.filter(
        (g) =>
          (g.fullName && g.fullName.toLowerCase().includes(kw)) ||
          (g.userCode && g.userCode.toLowerCase().includes(kw)) ||
          (g.email && g.email.toLowerCase().includes(kw))
      );
    }

    return [...list].sort((a, b) => {
      // 1. Đã tick chọn lên đầu
      const aChecked = selectedDispatchGuardIds.includes(a.id) ? 0 : 1;
      const bChecked = selectedDispatchGuardIds.includes(b.id) ? 0 : 1;
      if (aChecked !== bChecked) return aChecked - bChecked;

      // 2. Thứ tự ưu tiên nhóm: Cùng đội (0) > Chưa phân đội (1) > Đội khác (2)
      const aTeamName = a.teamName || a.team?.teamName;
      const bTeamName = b.teamName || b.team?.teamName;
      const aPriority = (aTeamName && assigningTeam?.teamName && aTeamName === assigningTeam.teamName)
        ? 0
        : (!aTeamName ? 1 : 2);
      const bPriority = (bTeamName && assigningTeam?.teamName && bTeamName === assigningTeam.teamName)
        ? 0
        : (!bTeamName ? 1 : 2);
      if (aPriority !== bPriority) return aPriority - bPriority;

      // 3. Trong cùng nhóm: Xếp theo mã bảo vệ
      return compareUserCodes(a, b);
    });
  }, [availableDispatchGuards, assigningTeam, assignModalTab, dispatchSearch, selectedDispatchGuardIds]);

  return (
    <div className="space-y-6">
      {/* 1. Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div className="schedule-kpi-card">
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
              Tổng Số Đội Bảo Vệ
            </span>
            <div className="text-2xl font-black text-slate-900 dark:text-white mt-1">
              {teams.length} <span className="text-sm font-normal text-slate-500">đội</span>
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
              Phân nhóm phục vụ chia ca trực tự động
            </p>
          </div>
          <div className="schedule-kpi-icon-wrap kpi-icon-blue">
            <Users size={22} />
          </div>
        </div>

        <div className="schedule-kpi-card">
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
              Quân Số Đã Vào Đội
            </span>
            <div className="text-2xl font-black text-emerald-600 dark:text-emerald-400 mt-1">
              {totalAssignedGuards} / {linkedGuards.length}{' '}
              <span className="text-sm font-normal text-slate-500">nhân viên</span>
            </div>
            <div className="flex items-center gap-1.5 mt-1 text-xs text-emerald-600 dark:text-emerald-400">
              <UserCheck size={14} /> Sẵn sàng nhận ca
            </div>
          </div>
          <div className="schedule-kpi-icon-wrap kpi-icon-emerald">
            <Shield size={22} />
          </div>
        </div>

        <div className="schedule-kpi-card">
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
              Chưa Phân Đội
            </span>
            <div className="text-2xl font-black text-amber-600 dark:text-amber-400 mt-1">
              {Math.max(0, linkedGuards.length - totalAssignedGuards)}{' '}
              <span className="text-sm font-normal text-slate-500">nhân viên</span>
            </div>
            <div className="flex items-center gap-1.5 mt-1 text-xs text-amber-600 dark:text-amber-400">
              <UserX size={14} /> Chờ FM phân bổ
            </div>
          </div>
          <div className="schedule-kpi-icon-wrap kpi-icon-amber">
            <Users size={22} />
          </div>
        </div>
      </div>

      {/* 2. Toolbar (Loại bỏ nút duplicate vì nút chính đã nằm trên Page Header) */}
      <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3">
        <div className="schedule-search-input-wrap flex-1 max-w-md">
          <Search size={16} className="text-slate-400 flex-shrink-0" />
          <input
            type="text"
            value={searchKeyword}
            onChange={(e) => setSearchKeyword(e.target.value)}
            placeholder="Tìm theo tên đội hoặc mô tả phân công..."
            className="w-full bg-transparent border-none outline-none text-xs text-slate-800 dark:text-slate-100 placeholder:text-slate-400"
          />
          {searchKeyword && (
            <button
              type="button"
              onClick={() => setSearchKeyword('')}
              className="text-slate-400 hover:text-slate-600 transition"
              title="Xóa tìm kiếm"
            >
              <X size={14} />
            </button>
          )}
        </div>

        <div className="text-xs font-semibold text-slate-500 dark:text-slate-400">
          Hiển thị <strong>{filteredTeams.length}</strong> / <strong>{teams.length}</strong> đội
        </div>
      </div>

      {/* Error alert */}
      {error && (
        <div className="p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-300 dark:border-rose-800 rounded-xl text-rose-800 dark:text-rose-200 text-xs flex items-center gap-2">
          <AlertCircle size={15} />
          <span>{error}</span>
        </div>
      )}

      {/* 3. Teams Grid */}
      {loading && teams.length === 0 ? (
        <div className="text-center py-16 text-slate-500 text-xs">
          Đang tải danh sách các đội bảo vệ...
        </div>
      ) : filteredTeams.length === 0 ? (
        <div className="schedule-empty-state">
          <div className="schedule-empty-state__icon schedule-empty-state__icon--primary">
            <Users size={28} />
          </div>
          <h3 className="schedule-empty-state__title">
            {searchKeyword ? 'Không tìm thấy đội nào phù hợp' : 'Chưa có đội bảo vệ nào'}
          </h3>
          <p className="schedule-empty-state__desc">
            {searchKeyword
              ? 'Hãy thử tìm kiếm với từ khóa khác hoặc xóa nội dung ô tìm kiếm.'
              : 'Hãy nhấn nút "Thêm Đội Mới" để thiết lập nhu cầu ca trực, nhận đề xuất quân số tự động và phân bổ các nhân viên bảo vệ vào đội.'}
          </p>
          {!searchKeyword && (
            <div className="schedule-empty-state__action">
              <button
                type="button"
                onClick={handleOpenCreate}
                className="schedule-btn-primary"
              >
                <Plus size={16} strokeWidth={2.5} />
                <span>Thêm Đội Mới</span>
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filteredTeams.map((team) => {
            // Find guards in this team (sắp xếp theo mã bảo vệ)
            const rawMembers = (team.members && team.members.length > 0)
              ? team.members
              : linkedGuards.filter(
                  (g) => g.team?.id === team.id || g.teamId === team.id
                );
            const members = [...rawMembers].sort(compareUserCodes);
            const memberCount = team.memberCount ?? members.length;
            const teamDispatches = dispatches.filter(
              (d) => d.toTeamId === team.id && d.status === 'ACTIVE'
            );

            return (
              <div
                key={team.id}
                className="team-card-modern"
              >
                <div
                  className="team-card-stripe"
                  style={{ backgroundColor: team.colorCode || '#2563eb' }}
                />

                <div>
                  {/* Card Header */}
                  <div className="flex items-center justify-between gap-2 mb-2 pt-1">
                    <div className="flex items-center gap-2.5">
                      <span
                        className="w-3.5 h-3.5 rounded-full ring-2 ring-white dark:ring-slate-900 shadow-sm flex-shrink-0"
                        style={{ backgroundColor: team.colorCode || '#2563eb' }}
                      />
                      <h4 className="font-bold text-base text-slate-900 dark:text-white">
                        {team.teamName}
                      </h4>
                    </div>
                    <div className="flex items-center gap-1.5 flex-wrap">
                      <span className="px-2 py-0.5 rounded-full text-xs font-bold bg-blue-50 dark:bg-blue-950/70 text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-800">
                        {memberCount} chính thức
                      </span>
                      {teamDispatches.length > 0 && (
                        <span
                          className="px-2 py-0.5 rounded-full text-[11px] font-bold bg-amber-50 dark:bg-amber-950/70 text-amber-700 dark:text-amber-300 border border-amber-200 dark:border-amber-800 flex items-center gap-1"
                          title="Số bảo vệ đang được điều động tăng cường cho sự kiện"
                        >
                          <Zap size={11} className="fill-amber-500" /> +{teamDispatches.length} tăng cường
                        </span>
                      )}
                    </div>
                  </div>

                  {/* Mô tả / Ghi chú đội (nếu có) */}
                  {team.description && team.description !== 'CO_SO_HCM' && team.description !== 'FPT_AROUND' && (
                    <p className="text-xs text-slate-500 dark:text-slate-400 mb-2 truncate">
                      {team.description}
                    </p>
                  )}

                  {/* Members list preview */}
                  <div className="border-t border-slate-100 dark:border-slate-700/60 pt-3">
                    <div className="text-[11px] font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider mb-2">
                      Danh sách thành viên ({members.length})
                    </div>
                    {members.length === 0 ? (
                      <div className="flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500 py-1.5 italic">
                        <UserX size={14} className="text-slate-400" />
                        <span>Chưa phân bổ nhân sự vào đội này</span>
                      </div>
                    ) : (
                      <div className="flex flex-wrap gap-1.5 max-h-24 overflow-y-auto pr-1">
                        {members.map((m) => (
                          <span
                            key={m.id}
                            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-medium bg-slate-100 dark:bg-slate-700/60 text-slate-700 dark:text-slate-200 border border-slate-200/60 dark:border-slate-600/40"
                          >
                            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                            {m.fullName || m.userCode}
                          </span>
                        ))}
                      </div>
                    )}

                    {/* Dispatched members for this team */}
                    {teamDispatches.length > 0 && (
                      <div className="mt-2.5 pt-2 border-t border-dashed border-amber-200 dark:border-amber-900/60">
                        <div className="text-[10px] font-bold text-amber-600 dark:text-amber-400 uppercase tracking-wider mb-1.5 flex items-center justify-between">
                          <span className="flex items-center gap-1">
                            <Zap size={11} className="fill-amber-500 text-amber-500" />
                            <span>Tăng cường sự kiện ({teamDispatches.length})</span>
                          </span>
                        </div>
                        <div className="flex flex-wrap gap-1.5 max-h-20 overflow-y-auto pr-0.5">
                          {teamDispatches.map((d) => (
                            <span
                              key={d.id}
                              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[10px] font-semibold bg-amber-50 dark:bg-amber-950/60 text-amber-800 dark:text-amber-200 border border-amber-200 dark:border-amber-800 shadow-xs"
                              title={`Tăng cường: ${d.startDate} -> ${d.endDate}${d.shiftType ? ' (' + (d.shiftType === 'SHIFT_MORNING' ? 'Ca Sáng' : d.shiftType === 'SHIFT_AFTERNOON' ? 'Ca Chiều' : 'Ca Đêm') + ')' : ''} | Lý do: ${d.reason || 'Sự kiện'}`}
                            >
                              <span className="w-1.5 h-1.5 rounded-full bg-amber-500 animate-pulse" />
                              <span className="truncate max-w-[100px]">{d.guardFullName || d.guardUserCode}</span>
                              {d.shiftType ? (
                                <span className="text-[9px] px-1 py-0.2 rounded bg-amber-200/80 dark:bg-amber-900/80 text-amber-900 dark:text-amber-100 font-bold">
                                  {d.shiftType === 'SHIFT_MORNING' ? 'Sáng' : d.shiftType === 'SHIFT_AFTERNOON' ? 'Chiều' : 'Đêm'}
                                </span>
                              ) : (
                                <span className="text-[9px] text-amber-600 dark:text-amber-400 font-mono">
                                  ({d.startDate ? d.startDate.slice(5) : ''}..{d.endDate ? d.endDate.slice(5) : ''})
                                </span>
                              )}
                              <button
                                type="button"
                                onClick={() => handleCancelDispatch(d.id)}
                                className="text-amber-400 hover:text-rose-600 transition ml-0.5"
                                title="Kết thúc sớm điều động này"
                              >
                                <X size={11} />
                              </button>
                            </span>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                </div>

                {/* Card Actions */}
                <div className="pt-4 mt-4 border-t border-slate-100 dark:border-slate-700/60 flex items-center justify-between gap-2">
                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      onClick={() => handleOpenEdit(team)}
                      className="p-1.5 rounded-lg text-slate-500 hover:text-slate-800 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 transition"
                      title="Chỉnh sửa thông tin đội"
                    >
                      <Edit2 size={15} />
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDeleteTeam(team)}
                      className="p-1.5 rounded-lg text-rose-500 hover:text-rose-700 hover:bg-rose-50 dark:hover:bg-rose-950/40 transition"
                      title="Xóa đội"
                    >
                      <Trash2 size={15} />
                    </button>
                  </div>

                  <button
                    type="button"
                    onClick={() => handleOpenAssignMembers(team)}
                    className="schedule-btn-secondary text-xs h-8 px-3"
                  >
                    <Users size={14} />
                    <span>Phân Bổ Quân Số</span>
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* 4. MODAL: MEMBER ASSIGNMENT & TEMPORARY DISPATCH */}
      {assigningTeam && (
        <div
          className="schedule-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget && !savingMembers && !savingDispatch) {
              setAssigningTeam(null);
            }
          }}
        >
          <div className="schedule-modal schedule-modal--lg max-w-2xl">
            <div className="schedule-modal__header pb-3">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__icon-badge">
                  <Users size={18} />
                </div>
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title">
                    Phân Bổ & Điều Động Quân Số — {assigningTeam.teamName}
                  </h3>
                  <p className="schedule-modal__subtitle">
                    {assignModalTab === 'PERMANENT'
                      ? 'Tích chọn bảo vệ để đưa vào đội chính thức. Quân số này sẽ được phân ca cùng nhau trong tuần.'
                      : 'Điều động nhân sự hỗ trợ cho sự kiện. Bảo vệ sẽ nhận cảnh báo sự cố tòa nhà và tự động hoàn trả khi hết hạn.'}
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={() => setAssigningTeam(null)}
                disabled={savingMembers || savingDispatch}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>

            {/* TAB SELECTOR */}
            <div className="flex border-b border-slate-200 dark:border-slate-700 px-6 pt-1 gap-2 bg-slate-50/60 dark:bg-slate-900/60">
              <button
                type="button"
                onClick={() => setAssignModalTab('PERMANENT')}
                className={`pb-2.5 pt-2 px-3 text-xs font-bold transition border-b-2 flex items-center gap-1.5 ${
                  assignModalTab === 'PERMANENT'
                    ? 'border-blue-600 text-blue-600 dark:text-blue-400'
                    : 'border-transparent text-slate-500 hover:text-slate-700 dark:text-slate-400'
                }`}
              >
                <Users size={14} />
                <span>Thành Viên Cố Định</span>
              </button>

              <button
                type="button"
                onClick={() => setAssignModalTab('DISPATCH')}
                className={`pb-2.5 pt-2 px-3 text-xs font-bold transition border-b-2 flex items-center gap-1.5 ${
                  assignModalTab === 'DISPATCH'
                    ? 'border-amber-500 text-amber-600 dark:text-amber-400'
                    : 'border-transparent text-slate-500 hover:text-slate-700 dark:text-slate-400'
                }`}
              >
                <Zap size={14} className="fill-amber-500 text-amber-500" />
                <span>⚡ Điều Động Tăng Cường (Sự Kiện)</span>
              </button>
            </div>

            {/* TAB 1: PERMANENT MEMBERS */}
            {assignModalTab === 'PERMANENT' ? (
              <>
                <div className="schedule-modal__body space-y-4">
                  <div className="flex items-center justify-between gap-3">
                    <div className="schedule-search-box flex-1 max-w-sm">
                      <Search size={13} className="schedule-search-icon" style={{ left: '10px' }} />
                      <input
                        type="text"
                        value={memberSearch}
                        onChange={(e) => setMemberSearch(e.target.value)}
                        placeholder="Tìm theo tên hoặc mã NV..."
                        className="schedule-search-input"
                        style={{ height: '32px', paddingLeft: '30px', paddingRight: '26px', fontSize: '12px' }}
                      />
                      {memberSearch && (
                        <button
                          type="button"
                          onClick={() => setMemberSearch('')}
                          className="schedule-search-clear"
                          title="Xóa tìm kiếm"
                        >
                          <X size={12} />
                        </button>
                      )}
                    </div>

                    <div className="text-xs font-bold text-blue-600 dark:text-blue-400 whitespace-nowrap">
                      Đã chọn: {selectedGuardIds.length} / {linkedGuards.length} bảo vệ
                    </div>
                  </div>

                  {/* Guards list */}
                  <div className="border border-slate-200 dark:border-slate-700 rounded-xl divide-y divide-slate-100 dark:divide-slate-800 max-h-80 overflow-y-auto bg-white dark:bg-slate-900 p-2">
                    {filteredGuardsForAssignment.length === 0 ? (
                      <div className="text-center py-8 text-xs text-slate-400">
                        Không tìm thấy nhân viên bảo vệ nào phù hợp
                      </div>
                    ) : (
                      filteredGuardsForAssignment.map((guard) => {
                        const isChecked = selectedGuardIds.includes(guard.id);
                        const guardCurrentTeam = guard.teamName || guard.team?.teamName;
                        const guardCurrentTeamId = guard.teamId || guard.team?.id;
                        const isOtherTeam =
                          guardCurrentTeamId && guardCurrentTeamId !== assigningTeam.id;

                        return (
                          <label
                            key={guard.id}
                            className={`flex items-center justify-between p-2.5 rounded-lg cursor-pointer transition text-xs ${
                              isChecked
                                ? 'bg-blue-50/70 dark:bg-blue-950/40'
                                : 'hover:bg-slate-50 dark:hover:bg-slate-800/60'
                            }`}
                          >
                            <div className="flex items-center gap-3">
                              <input
                                type="checkbox"
                                checked={isChecked}
                                onChange={() => handleToggleGuard(guard.id)}
                                className="rounded border-slate-300 text-blue-600 focus:ring-blue-500 h-4 w-4"
                              />
                              <div>
                                <div className="font-bold text-slate-800 dark:text-slate-100">
                                  {guard.fullName}
                                </div>
                                <div className="text-[11px] text-slate-500 dark:text-slate-400 font-mono">
                                  {guard.userCode || 'NV-BV'} — {guard.email}
                                </div>
                              </div>
                            </div>

                            {guardCurrentTeam && (
                              <span
                                className={`px-2 py-0.5 rounded text-[10px] font-semibold ${
                                  isOtherTeam
                                    ? 'bg-amber-50 dark:bg-amber-950/50 text-amber-700 dark:text-amber-300 border border-amber-200 dark:border-amber-800'
                                    : 'bg-blue-50 dark:bg-blue-950/50 text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-800'
                                }`}
                              >
                                {guardCurrentTeam}
                              </span>
                            )}
                          </label>
                        );
                      })
                    )}
                  </div>
                </div>

                <div className="schedule-modal__footer flex items-center justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => setAssigningTeam(null)}
                    disabled={savingMembers}
                    className="schedule-btn-secondary"
                  >
                    Hủy
                  </button>
                  <button
                    type="button"
                    onClick={handleSaveMembers}
                    disabled={savingMembers}
                    className="schedule-btn-primary"
                  >
                    {savingMembers ? 'Đang Lưu...' : 'Lưu Danh Sách Thành Viên'}
                  </button>
                </div>
              </>
            ) : (
              /* TAB 2: TEMPORARY DISPATCH */
              <>
                <div className="schedule-modal__body space-y-4">
                  {/* Info Banner */}
                  <div className="p-3 bg-amber-50 dark:bg-amber-950/40 border border-amber-200 dark:border-amber-800/80 rounded-xl text-xs text-amber-800 dark:text-amber-200 flex items-start gap-2.5">
                    <Zap size={16} className="text-amber-600 fill-amber-500 shrink-0 mt-0.5" />
                    <div className="leading-relaxed">
                      <strong>Tăng cường theo thời gian:</strong> Nhân sự được chọn sẽ tạm thời thuộc về <strong>{assigningTeam.teamName}</strong> trong khoảng ngày này để nhận cảnh báo sự cố an ninh (Incident Alert) tại tòa nhà và được phân ca sự kiện. Khi qua ngày kết thúc, hệ thống sẽ <strong>tự động hoàn trả</strong> bảo vệ về đội gốc.
                    </div>
                  </div>

                  {/* Date Range, Shift Type & Reason inputs */}
                  <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 gap-3 p-3 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700 rounded-xl">
                    <div>
                      <label className="block text-[11px] font-bold text-slate-700 dark:text-slate-300 mb-1">
                        Từ ngày <span className="text-rose-500">*</span>
                      </label>
                      <input
                        type="date"
                        value={dispatchStartDate}
                        onChange={(e) => setDispatchStartDate(e.target.value)}
                        className="w-full px-2.5 py-1.5 text-xs rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 font-medium"
                      />
                    </div>
                    <div>
                      <label className="block text-[11px] font-bold text-slate-700 dark:text-slate-300 mb-1">
                        Đến ngày <span className="text-rose-500">*</span>
                      </label>
                      <input
                        type="date"
                        value={dispatchEndDate}
                        min={dispatchStartDate}
                        onChange={(e) => setDispatchEndDate(e.target.value)}
                        className="w-full px-2.5 py-1.5 text-xs rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 font-medium"
                      />
                    </div>
                    <div>
                      <label className="block text-[11px] font-bold text-slate-700 dark:text-slate-300 mb-1">
                        Khung ca tăng cường
                      </label>
                      <select
                        value={dispatchShiftType}
                        onChange={(e) => setDispatchShiftType(e.target.value)}
                        className="w-full px-2.5 py-1.5 text-xs rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 font-medium"
                      >
                        <option value="ALL">Tất cả các ca (Cả ngày)</option>
                        <option value="SHIFT_MORNING">Ca Sáng (06:00 - 14:00)</option>
                        <option value="SHIFT_AFTERNOON">Ca Chiều (14:00 - 22:00)</option>
                        <option value="SHIFT_NIGHT">Ca Đêm (22:00 - 06:00)</option>
                      </select>
                    </div>
                    <div>
                      <label className="block text-[11px] font-bold text-slate-700 dark:text-slate-300 mb-1">
                        Tên sự kiện / Lý do
                      </label>
                      <input
                        type="text"
                        value={dispatchReason}
                        onChange={(e) => setDispatchReason(e.target.value)}
                        placeholder="VD: Lễ Tốt Nghiệp, Hội thao..."
                        className="w-full px-2.5 py-1.5 text-xs rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100"
                      />
                    </div>
                  </div>

                  {/* Guard search & count */}
                  <div className="space-y-2">
                    <div className="flex items-center justify-between gap-3">
                      <div className="schedule-search-box flex-1 max-w-sm">
                        <Search size={13} className="schedule-search-icon" style={{ left: '10px' }} />
                        <input
                          type="text"
                          value={dispatchSearch}
                          onChange={(e) => setDispatchSearch(e.target.value)}
                          placeholder="Tìm bảo vệ từ các đội khác để điều động..."
                          className="schedule-search-input"
                          style={{ height: '32px', paddingLeft: '30px', paddingRight: '26px', fontSize: '12px' }}
                        />
                        {dispatchSearch && (
                          <button
                            type="button"
                            onClick={() => setDispatchSearch('')}
                            className="schedule-search-clear"
                            title="Xóa tìm kiếm"
                          >
                            <X size={12} />
                          </button>
                        )}
                      </div>

                      <div className="text-xs font-bold text-amber-600 dark:text-amber-400 whitespace-nowrap">
                        Đã chọn: {selectedDispatchGuardIds.length} bảo vệ tăng cường
                      </div>
                    </div>

                    {/* Dispatched eligible guards list */}
                    <div className="border border-slate-200 dark:border-slate-700 rounded-xl divide-y divide-slate-100 dark:divide-slate-800 max-h-60 overflow-y-auto bg-white dark:bg-slate-900 p-2">
                      {loadingAvailableGuards ? (
                        <div className="text-center py-8 text-xs text-slate-400 flex items-center justify-center gap-2">
                          <div className="w-4 h-4 border-2 border-amber-500 border-t-transparent rounded-full animate-spin" />
                          <span>Đang kiểm tra lịch trực và tìm bảo vệ khả dụng...</span>
                        </div>
                      ) : filteredGuardsForDispatch.length === 0 ? (
                        <div className="text-center py-8 text-xs text-slate-400">
                          {dispatchSearch.trim()
                            ? 'Không tìm thấy bảo vệ nào phù hợp từ khóa'
                            : 'Không có bảo vệ nào khả dụng (không vướng lịch trực hoặc lịch liên tiếp) trong khoảng thời gian này'}
                        </div>
                      ) : (
                        filteredGuardsForDispatch.map((guard) => {
                          const isChecked = selectedDispatchGuardIds.includes(guard.id);
                          const currentTeamName = guard.teamName || guard.team?.teamName;
                          const isSameTeam = currentTeamName && assigningTeam?.teamName && currentTeamName === assigningTeam.teamName;

                          return (
                            <label
                              key={guard.id}
                              className={`flex items-center justify-between p-2.5 rounded-lg cursor-pointer transition text-xs ${
                                isChecked
                                  ? 'bg-amber-50/80 dark:bg-amber-950/40'
                                  : 'hover:bg-slate-50 dark:hover:bg-slate-800/60'
                              }`}
                            >
                              <div className="flex items-center gap-3">
                                <input
                                  type="checkbox"
                                  checked={isChecked}
                                  onChange={() => handleToggleDispatchGuard(guard.id)}
                                  className="rounded border-slate-300 text-amber-600 focus:ring-amber-500 h-4 w-4"
                                />
                                <div>
                                  <div className="font-bold text-slate-800 dark:text-slate-100 flex items-center gap-2">
                                    <span>{guard.fullName}</span>
                                    <span className="px-1.5 py-0.2 rounded text-[10px] font-medium bg-emerald-50 dark:bg-emerald-950/50 text-emerald-700 dark:text-emerald-300 border border-emerald-200 dark:border-emerald-800">
                                      Khả dụng
                                    </span>
                                  </div>
                                  <div className="text-[11px] text-slate-500 dark:text-slate-400 font-mono">
                                    {guard.userCode || 'NV-BV'} — {guard.email}
                                  </div>
                                </div>
                              </div>

                              <span
                                className={`px-2 py-0.5 rounded text-[10px] font-semibold border ${
                                  isSameTeam
                                    ? 'bg-blue-50 dark:bg-blue-950/50 text-blue-700 dark:text-blue-300 border-blue-200 dark:border-blue-800'
                                    : 'bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 border border-slate-200 dark:border-slate-700'
                                }`}
                              >
                                {currentTeamName ? (isSameTeam ? `${currentTeamName} (Cùng đội)` : `Đội: ${currentTeamName}`) : 'Chưa phân đội'}
                              </span>
                            </label>
                          );
                        })
                      )}
                    </div>
                  </div>
                </div>

                <div className="schedule-modal__footer flex items-center justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => setAssigningTeam(null)}
                    disabled={savingDispatch}
                    className="schedule-btn-secondary"
                  >
                    Hủy
                  </button>
                  <button
                    type="button"
                    onClick={handleSaveDispatch}
                    disabled={savingDispatch || selectedDispatchGuardIds.length === 0}
                    className="px-4 py-2 text-xs font-bold rounded-xl bg-amber-600 hover:bg-amber-700 text-white shadow-sm transition flex items-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <Zap size={13} className="fill-white" />
                    <span>
                      {savingDispatch
                        ? 'Đang Xử Lý...'
                        : `Xác Nhận Điều Động (${selectedDispatchGuardIds.length} BV)`}
                    </span>
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* 5. MODAL: CREATE / EDIT TEAM */}
      {isCreateModalOpen && (
        <div
          className="schedule-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget && !submittingTeam) {
              handleCloseModal();
            }
          }}
        >
          <div
            className={`schedule-modal ${editingTeam ? 'schedule-modal--md' : 'schedule-modal--lg'}`}
            style={{ maxWidth: editingTeam ? '500px' : '660px', width: '100%' }}
          >
            <div className="schedule-modal__header">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__icon-badge">
                  <Users size={18} />
                </div>
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title">
                    {editingTeam ? 'Chỉnh Sửa Đội Bảo Vệ' : 'Tạo Đội Bảo Vệ'}
                  </h3>
                  <p className="schedule-modal__subtitle">
                    {editingTeam
                      ? 'Thiết lập thông tin đội bảo vệ'
                      : 'Thiết lập số người/ca để tính quân số đề xuất cho đội'}
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={handleCloseModal}
                disabled={submittingTeam}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>

            <form onSubmit={handleSaveTeam}>
              <div className="schedule-modal__body space-y-3.5 overflow-x-hidden">
                {/* THÔNG TIN CƠ BẢN */}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 mb-1">
                      Tên Đội Bảo Vệ <span className="text-rose-500">*</span>
                    </label>
                    <input
                      type="text"
                      placeholder="Nhập tên đội bảo vệ"
                      value={teamForm.teamName}
                      onChange={(e) =>
                        setTeamForm((prev) => ({ ...prev, teamName: e.target.value }))
                      }
                      required
                      className="w-full px-3 py-2 text-xs font-medium rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  </div>

                  <div>
                    <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 mb-1">
                      Mô Tả / Ghi Chú (Tùy chọn)
                    </label>
                    <input
                      type="text"
                      placeholder="Nhập ghi chú hoặc nhiệm vụ (không bắt buộc)"
                      value={teamForm.description}
                      onChange={(e) =>
                        setTeamForm((prev) => ({ ...prev, description: e.target.value }))
                      }
                      className="w-full px-3 py-2 text-xs font-medium rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  </div>
                </div>

                {/* Khi tạo mới: Nhu cầu theo ca + Đề xuất quân số + Phân bổ bảo vệ */}
                {!editingTeam ? (
                  <>
                    {/* BẢNG CẤU HÌNH NHU CẦU THEO CA */}
                    <div className="space-y-1.5 pt-1">
                      <label className="text-xs font-bold text-slate-700 dark:text-slate-200">
                        Nhu Cầu Quân Số Theo Ca
                      </label>

                      <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 overflow-hidden shadow-sm">
                        <table className="w-full text-xs text-left">
                          <thead className="bg-slate-50 dark:bg-slate-800/70 text-slate-600 dark:text-slate-300 border-b border-slate-200 dark:border-slate-700 font-semibold">
                            <tr>
                              <th className="py-2.5 px-3">Thời gian</th>
                              <th className="py-2.5 px-2 text-center">Ca Sáng</th>
                              <th className="py-2.5 px-2 text-center">Ca Chiều</th>
                              <th className="py-2.5 px-2 text-center">Ca Đêm</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                            {/* Hàng 1: T2 - T7 */}
                            <tr className="hover:bg-slate-50/50 dark:hover:bg-slate-800/30">
                              <td className="py-2.5 px-3 font-semibold text-slate-800 dark:text-slate-100 whitespace-nowrap">
                                Thứ 2 – Thứ 7
                              </td>
                              <td className="py-2.5 px-2 text-center">
                                <div className="inline-flex items-center rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 overflow-hidden">
                                  <button
                                    type="button"
                                    onClick={() => setWeekdayMorningDemand(Math.max(2, m_wd - 1))}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    -
                                  </button>
                                  <input
                                    type="number"
                                    min="2"
                                    max="50"
                                    value={weekdayMorningDemand}
                                    onChange={(e) => setWeekdayMorningDemand(Math.max(2, parseInt(e.target.value, 10) || 2))}
                                    className="w-8 text-center font-bold text-xs py-0.5 bg-transparent text-slate-800 dark:text-slate-100 focus:outline-none [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                                  />
                                  <button
                                    type="button"
                                    onClick={() => setWeekdayMorningDemand(m_wd + 1)}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    +
                                  </button>
                                </div>
                              </td>
                              <td className="py-2.5 px-2 text-center">
                                <div className="inline-flex items-center rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 overflow-hidden">
                                  <button
                                    type="button"
                                    onClick={() => setWeekdayAfternoonDemand(Math.max(2, a_wd - 1))}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    -
                                  </button>
                                  <input
                                    type="number"
                                    min="2"
                                    max="50"
                                    value={weekdayAfternoonDemand}
                                    onChange={(e) => setWeekdayAfternoonDemand(Math.max(2, parseInt(e.target.value, 10) || 2))}
                                    className="w-8 text-center font-bold text-xs py-0.5 bg-transparent text-slate-800 dark:text-slate-100 focus:outline-none [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                                  />
                                  <button
                                    type="button"
                                    onClick={() => setWeekdayAfternoonDemand(a_wd + 1)}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    +
                                  </button>
                                </div>
                              </td>
                              <td className="py-2.5 px-2 text-center">
                                <div className="inline-flex items-center rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 overflow-hidden">
                                  <button
                                    type="button"
                                    onClick={() => setWeekdayNightDemand(Math.max(2, n_wd - 1))}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    -
                                  </button>
                                  <input
                                    type="number"
                                    min="2"
                                    max="50"
                                    value={weekdayNightDemand}
                                    onChange={(e) => setWeekdayNightDemand(Math.max(2, parseInt(e.target.value, 10) || 2))}
                                    className="w-8 text-center font-bold text-xs py-0.5 bg-transparent text-slate-800 dark:text-slate-100 focus:outline-none [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                                  />
                                  <button
                                    type="button"
                                    onClick={() => setWeekdayNightDemand(n_wd + 1)}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    +
                                  </button>
                                </div>
                              </td>
                            </tr>

                            {/* Hàng 2: Chủ Nhật */}
                            <tr className="hover:bg-slate-50/50 dark:hover:bg-slate-800/30">
                              <td className="py-2.5 px-3 font-semibold text-slate-800 dark:text-slate-100 whitespace-nowrap">
                                Chủ Nhật
                              </td>
                              <td className="py-2.5 px-2 text-center">
                                <div className="inline-flex items-center rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 overflow-hidden">
                                  <button
                                    type="button"
                                    onClick={() => setSundayMorningDemand(Math.max(2, m_su - 1))}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    -
                                  </button>
                                  <input
                                    type="number"
                                    min="2"
                                    max="50"
                                    value={sundayMorningDemand}
                                    onChange={(e) => setSundayMorningDemand(Math.max(2, parseInt(e.target.value, 10) || 2))}
                                    className="w-8 text-center font-bold text-xs py-0.5 bg-transparent text-slate-800 dark:text-slate-100 focus:outline-none [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                                  />
                                  <button
                                    type="button"
                                    onClick={() => setSundayMorningDemand(m_su + 1)}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    +
                                  </button>
                                </div>
                              </td>
                              <td className="py-2.5 px-2 text-center">
                                <div className="inline-flex items-center rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 overflow-hidden">
                                  <button
                                    type="button"
                                    onClick={() => setSundayAfternoonDemand(Math.max(2, a_su - 1))}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    -
                                  </button>
                                  <input
                                    type="number"
                                    min="2"
                                    max="50"
                                    value={sundayAfternoonDemand}
                                    onChange={(e) => setSundayAfternoonDemand(Math.max(2, parseInt(e.target.value, 10) || 2))}
                                    className="w-8 text-center font-bold text-xs py-0.5 bg-transparent text-slate-800 dark:text-slate-100 focus:outline-none [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                                  />
                                  <button
                                    type="button"
                                    onClick={() => setSundayAfternoonDemand(a_su + 1)}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    +
                                  </button>
                                </div>
                              </td>
                              <td className="py-2.5 px-2 text-center">
                                <div className="inline-flex items-center rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 overflow-hidden">
                                  <button
                                    type="button"
                                    onClick={() => setSundayNightDemand(Math.max(2, n_su - 1))}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    -
                                  </button>
                                  <input
                                    type="number"
                                    min="2"
                                    max="50"
                                    value={sundayNightDemand}
                                    onChange={(e) => setSundayNightDemand(Math.max(2, parseInt(e.target.value, 10) || 2))}
                                    className="w-8 text-center font-bold text-xs py-0.5 bg-transparent text-slate-800 dark:text-slate-100 focus:outline-none [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                                  />
                                  <button
                                    type="button"
                                    onClick={() => setSundayNightDemand(n_su + 1)}
                                    className="w-6 h-6 flex items-center justify-center text-slate-500 hover:text-slate-900 dark:hover:text-white hover:bg-slate-200 dark:hover:bg-slate-700 font-bold text-xs transition"
                                  >
                                    +
                                  </button>
                                </div>
                              </td>
                            </tr>
                          </tbody>
                        </table>
                      </div>
                    </div>

                    {/* ĐỀ XUẤT QUÂN SỐ TỐI GIẢN */}
                    <div className="px-3.5 py-2.5 rounded-xl bg-blue-50/60 dark:bg-blue-950/30 border border-blue-100 dark:border-blue-900/40 flex items-center justify-between gap-3 text-xs">
                      <div className="flex items-center gap-2">
                        <span className="text-slate-600 dark:text-slate-300">
                          Nhu cầu: <strong className="text-slate-800 dark:text-slate-100 font-bold">{weeklyDemand} lượt trực/tuần</strong>
                        </span>
                        <span className="text-slate-300 dark:text-slate-600">•</span>
                        <span className="text-slate-600 dark:text-slate-300">
                          Đề xuất: <strong className="text-blue-600 dark:text-blue-400 font-bold">{minGuardsRecommended} bảo vệ</strong>
                        </span>
                      </div>

                      <span
                        className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-bold border ${
                          newTeamMemberIds.length >= minGuardsRecommended && minGuardsRecommended > 0
                            ? 'bg-emerald-50 dark:bg-emerald-950/60 text-emerald-700 dark:text-emerald-300 border-emerald-200 dark:border-emerald-800'
                            : 'bg-amber-50 dark:bg-amber-950/60 text-amber-700 dark:text-amber-300 border-amber-200 dark:border-amber-800'
                        }`}
                      >
                        {newTeamMemberIds.length >= minGuardsRecommended && minGuardsRecommended > 0 ? (
                          <CheckCircle2 size={12} />
                        ) : (
                          <AlertCircle size={12} />
                        )}
                        Đã chọn: {newTeamMemberIds.length}/{minGuardsRecommended} BV
                      </span>
                    </div>

                    {/* PHÂN BỔ THÀNH VIÊN VÀO ĐỘI */}
                    <div className="space-y-2 pt-1">
                      <div className="flex items-center justify-between gap-2">
                        <label className="text-xs font-bold text-slate-700 dark:text-slate-300">
                          Chọn bảo vệ vào đội
                        </label>

                        <div className="flex items-center gap-2">
                          <button
                            type="button"
                            onClick={handleQuickSelectUnassigned}
                            className="px-2.5 py-1 rounded-lg text-xs font-semibold bg-blue-50 hover:bg-blue-100 dark:bg-blue-950/60 dark:hover:bg-blue-900/60 text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-800 transition flex items-center gap-1.5"
                          >
                            <Zap size={12} className="text-amber-500 fill-amber-500" />
                            <span>Chọn nhanh {minGuardsRecommended} bảo vệ</span>
                          </button>

                          {newTeamMemberIds.length > 0 && (
                            <button
                              type="button"
                              onClick={() => setNewTeamMemberIds([])}
                              className="text-xs text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition"
                            >
                              Bỏ chọn ({newTeamMemberIds.length})
                            </button>
                          )}
                        </div>
                      </div>

                      {/* Search box for guards */}
                      <div className="schedule-search-box w-full max-w-sm">
                        <Search size={13} className="schedule-search-icon" style={{ left: '10px' }} />
                        <input
                          type="text"
                          value={createMemberSearch}
                          onChange={(e) => setCreateMemberSearch(e.target.value)}
                          placeholder="Tìm theo tên, mã NV..."
                          className="schedule-search-input"
                          style={{ height: '32px', paddingLeft: '30px', paddingRight: '26px', fontSize: '12px' }}
                        />
                        {createMemberSearch && (
                          <button
                            type="button"
                            onClick={() => setCreateMemberSearch('')}
                            className="schedule-search-clear"
                            title="Xóa tìm kiếm"
                          >
                            <X size={12} />
                          </button>
                        )}
                      </div>

                      {/* Guard list */}
                      <div className="border border-slate-200 dark:border-slate-700 rounded-xl divide-y divide-slate-100 dark:divide-slate-800 max-h-44 overflow-y-auto bg-white dark:bg-slate-900 p-1">
                        {filteredGuardsForCreate.length === 0 ? (
                          <div className="text-center py-5 text-xs text-slate-400">
                            Không tìm thấy nhân viên bảo vệ nào phù hợp
                          </div>
                        ) : (
                          filteredGuardsForCreate.map((guard) => {
                            const isChecked = newTeamMemberIds.includes(guard.id);
                            const guardTeamName = guard.teamName || guard.team?.teamName;

                            return (
                              <label
                                key={guard.id}
                                className={`flex items-center justify-between p-2 rounded-lg cursor-pointer transition text-xs ${
                                  isChecked
                                    ? 'bg-blue-50/70 dark:bg-blue-950/40'
                                    : 'hover:bg-slate-50 dark:hover:bg-slate-800/60'
                                }`}
                              >
                                <div className="flex items-center gap-2.5">
                                  <input
                                    type="checkbox"
                                    checked={isChecked}
                                    onChange={() => handleToggleCreateMember(guard.id)}
                                    className="rounded border-slate-300 text-blue-600 focus:ring-blue-500 h-4 w-4"
                                  />
                                  <div>
                                    <span className="font-bold text-slate-800 dark:text-slate-100">
                                      {guard.fullName}
                                    </span>
                                    <span className="text-[11px] text-slate-400 font-mono ml-2">
                                      ({guard.userCode || 'NV-BV'})
                                    </span>
                                  </div>
                                </div>

                                {guardTeamName ? (
                                  <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-amber-50 dark:bg-amber-950/50 text-amber-700 dark:text-amber-300 border border-amber-200 dark:border-amber-800">
                                    {guardTeamName}
                                  </span>
                                ) : (
                                  <span className="px-2 py-0.5 rounded text-[10px] font-medium bg-emerald-50 dark:bg-emerald-950/50 text-emerald-700 dark:text-emerald-300 border border-emerald-200 dark:border-emerald-800">
                                    Chưa phân đội
                                  </span>
                                )}
                              </label>
                            );
                          })
                        )}
                      </div>
                    </div>
                  </>
                ) : (
                  <div className="p-3 bg-blue-50 dark:bg-blue-950/40 border border-blue-200 dark:border-blue-800 rounded-xl text-xs text-blue-800 dark:text-blue-300 flex items-center gap-2">
                    <Users size={16} className="shrink-0 text-blue-600" />
                    <span>
                      Để thêm hoặc điều chỉnh danh sách thành viên cho đội này, vui lòng sử dụng nút <strong>"Phân Bổ Quân Số"</strong> ngay trên thẻ của đội.
                    </span>
                  </div>
                )}
              </div>

              <div className="schedule-modal__footer flex items-center justify-end gap-2">
                <button
                  type="button"
                  onClick={handleCloseModal}
                  disabled={submittingTeam}
                  className="schedule-btn-secondary"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={submittingTeam}
                  className="schedule-btn-primary"
                >
                  {submittingTeam
                    ? 'Đang Lưu...'
                    : editingTeam
                    ? 'Cập Nhật Đội'
                    : newTeamMemberIds.length > 0
                    ? `Tạo Đội & Gán ${newTeamMemberIds.length} Bảo Vệ`
                    : 'Tạo Đội Mới'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
