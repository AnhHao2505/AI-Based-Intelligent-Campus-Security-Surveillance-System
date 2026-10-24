import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../../../core/utils/storage_helper.dart';
import '../../map/providers/map_provider.dart';
import '../../map/screens/guard_map_screen.dart';
import '../models/incident_model.dart';
import '../providers/incident_provider.dart';
import '../widgets/incident_detail_sheet.dart';

enum IncidentFilterTab {
  pending,
  claimed,
  resolved,
  all,
}

class IncidentListScreen extends StatefulWidget {
  const IncidentListScreen({super.key});

  @override
  State<IncidentListScreen> createState() => _IncidentListScreenState();
}

class _IncidentListScreenState extends State<IncidentListScreen> {
  IncidentFilterTab _selectedTab = IncidentFilterTab.pending;
  List<IncidentModel> _historyIncidents = [];
  bool _isLoadingHistory = false;
  String? _currentUserId;

  @override
  void initState() {
    super.initState();
    _loadCurrentUser();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final incidentProvider = context.read<IncidentProvider>();
      if (!incidentProvider.isStompConnected) {
        incidentProvider.initStomp();
      }
      incidentProvider.loadActiveIncidents();
      _fetchIncidentsHistory();
    });
  }

  Future<void> _loadCurrentUser() async {
    final user = await StorageHelper.getUser();
    if (mounted) {
      setState(() {
        _currentUserId = user?.id;
      });
    }
  }

  Future<void> _fetchIncidentsHistory() async {
    if (_isLoadingHistory) return;
    setState(() => _isLoadingHistory = true);
    try {
      final incidentProvider = context.read<IncidentProvider>();
      final list = await incidentProvider.incidentService.getIncidents();
      if (mounted) {
        setState(() {
          _historyIncidents = list;
        });
      }
    } catch (e) {
      debugPrint('[IncidentListScreen] Error loading history: $e');
    } finally {
      if (mounted) {
        setState(() => _isLoadingHistory = false);
      }
    }
  }

  Color _getSeverityColor(int weight) {
    switch (weight) {
      case 3:
        return const Color(0xFFEF4444); // Red
      case 2:
        return const Color(0xFFF59E0B); // Amber / Orange
      case 1:
      default:
        return const Color(0xFF8B5CF6); // Purple
    }
  }

  String _cleanDetails(String? text) {
    if (text == null || text.trim().isEmpty) {
      return 'Phát hiện sự cố an ninh tại khu vực giám sát.';
    }
    return text.replaceAll(RegExp(r'\[[A-Z0-9_]+\]\s*'), '').trim();
  }

  String _formatTime(DateTime? dt) {
    if (dt == null) return 'Vừa phát hiện';
    final now = DateTime.now();
    final diff = now.difference(dt);
    if (diff.inMinutes < 1) return 'Vừa xong';
    if (diff.inMinutes < 60) return '${diff.inMinutes} phút trước';
    if (diff.inHours < 24) return DateFormat('HH:mm').format(dt);
    return DateFormat('dd/MM HH:mm').format(dt);
  }

  List<IncidentModel> _getFilteredList(IncidentProvider provider) {
    final activeQueue = provider.alertQueue;
    final activeIncident = provider.activeIncident;

    // Gộp sự cố realtime và lịch sử từ API
    final Map<String, IncidentModel> merged = {};

    for (final inc in _historyIncidents) {
      merged[inc.id] = inc;
    }
    for (final inc in activeQueue) {
      merged[inc.id] = inc;
    }
    if (activeIncident != null) {
      merged[activeIncident.id] = activeIncident;
    }

    final allList = merged.values.toList()
      ..sort((a, b) {
        final sevCompare = b.severityWeight.compareTo(a.severityWeight);
        if (sevCompare != 0) return sevCompare;

        final dateA = a.detectedAt ?? DateTime.fromMillisecondsSinceEpoch(0);
        final dateB = b.detectedAt ?? DateTime.fromMillisecondsSinceEpoch(0);
        return dateB.compareTo(dateA);
      });

    switch (_selectedTab) {
      case IncidentFilterTab.pending:
        return allList.where((i) => i.isNew).toList();
      case IncidentFilterTab.claimed:
        return allList.where((i) => i.isClaimed).toList();
      case IncidentFilterTab.resolved:
        return allList.where((i) => i.isResolved).toList();
      case IncidentFilterTab.all:
        return allList;
    }
  }

  void _showImagePreview(BuildContext context, String imageUrl) {
    showDialog(
      context: context,
      builder: (ctx) => Dialog(
        backgroundColor: Colors.black,
        insetPadding: const EdgeInsets.all(12),
        child: Stack(
          alignment: Alignment.topRight,
          children: [
            InteractiveViewer(
              minScale: 0.8,
              maxScale: 4.0,
              child: Center(
                child: Image.network(
                  imageUrl,
                  fit: BoxFit.contain,
                  loadingBuilder: (context, child, progress) {
                    if (progress == null) return child;
                    return const Center(
                      child: CircularProgressIndicator(color: AppColors.primaryLight),
                    );
                  },
                  errorBuilder: (context, error, stackTrace) => const Center(
                    child: Text(
                      'Không tải được hình ảnh',
                      style: TextStyle(color: Colors.white70),
                    ),
                  ),
                ),
              ),
            ),
            IconButton(
              icon: const Icon(Icons.close, color: Colors.white),
              onPressed: () => Navigator.pop(ctx),
            ),
          ],
        ),
      ),
    );
  }

  void _navigateToMap(IncidentModel incident) {
    final mapProvider = context.read<MapProvider>();
    final pin = mapProvider.findPinForIncident(
      areaId: incident.areaId,
      areaName: incident.areaName,
      cameraCode: incident.cameraCode,
    );

    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => GuardMapScreen(initialDestination: pin),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final incidentProvider = context.watch<IncidentProvider>();
    final filteredList = _getFilteredList(incidentProvider);
    final pendingCount = incidentProvider.pendingCount;

    return Scaffold(
      backgroundColor: AppColors.bg(context),
      appBar: AppBar(
        backgroundColor: AppColors.crd(context),
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_rounded, color: AppColors.txtPrimary(context)),
          onPressed: () => Navigator.pop(context),
          tooltip: 'Quay lại',
        ),
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(
                  'Sự Cố An Ninh',
                  style: TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.bold,
                    color: AppColors.txtPrimary(context),
                  ),
                ),
                const SizedBox(width: 8),
                // Chấm trạng thái kết nối STOMP
                Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: incidentProvider.isStompConnected
                        ? const Color(0xFF10B981)
                        : const Color(0xFFF59E0B),
                  ),
                ),
                const SizedBox(width: 4),
                Text(
                  incidentProvider.isStompConnected ? 'Trực tiếp' : 'Mất kết nối',
                  style: TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.w500,
                    color: incidentProvider.isStompConnected
                        ? const Color(0xFF10B981)
                        : const Color(0xFFF59E0B),
                  ),
                ),
              ],
            ),
            Text(
              '${filteredList.length} sự cố được ghi nhận',
              style: TextStyle(fontSize: 11, color: AppColors.txtSecondary(context)),
            ),
          ],
        ),
        actions: [
          // Nút mở bản đồ
          IconButton(
            icon: const Icon(Icons.map_outlined, color: AppColors.primaryLight, size: 22),
            tooltip: 'Mở bản đồ tuần tra',
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const GuardMapScreen()),
              );
            },
          ),
          // Nút làm mới
          IconButton(
            icon: Icon(Icons.refresh_rounded, color: AppColors.txtSecondary(context), size: 22),
            tooltip: 'Làm mới danh sách',
            onPressed: () {
              incidentProvider.loadActiveIncidents();
              _fetchIncidentsHistory();
            },
          ),
          const SizedBox(width: 4),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(48),
          child: _buildFilterTabs(pendingCount),
        ),
      ),
      body: RefreshIndicator(
        color: AppColors.primaryLight,
        onRefresh: () async {
          await incidentProvider.loadActiveIncidents();
          await _fetchIncidentsHistory();
        },
        child: filteredList.isEmpty
            ? _buildEmptyState()
            : ListView.separated(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
                itemCount: filteredList.length,
                separatorBuilder: (ctx, i) => const SizedBox(height: 14),
                itemBuilder: (context, index) {
                  final incident = filteredList[index];
                  return _buildIncidentCard(incident, incidentProvider);
                },
              ),
      ),
    );
  }

  Widget _buildFilterTabs(int pendingCount) {
    return Container(
      height: 48,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      decoration: BoxDecoration(
        color: AppColors.crd(context),
        border: Border(
          bottom: BorderSide(color: AppColors.crdBorder(context), width: 1),
        ),
      ),
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          _buildTabPill(
            tab: IncidentFilterTab.pending,
            label: 'Cần xử lý',
            badgeCount: pendingCount,
            color: const Color(0xFFEF4444),
          ),
          const SizedBox(width: 8),
          _buildTabPill(
            tab: IncidentFilterTab.claimed,
            label: 'Đang xử lý',
            badgeCount: null,
            color: const Color(0xFF3B82F6),
          ),
          const SizedBox(width: 8),
          _buildTabPill(
            tab: IncidentFilterTab.resolved,
            label: 'Đã giải quyết',
            badgeCount: null,
            color: const Color(0xFF10B981),
          ),
          const SizedBox(width: 8),
          _buildTabPill(
            tab: IncidentFilterTab.all,
            label: 'Tất cả',
            badgeCount: null,
            color: AppColors.primaryLight,
          ),
        ],
      ),
    );
  }

  Widget _buildTabPill({
    required IncidentFilterTab tab,
    required String label,
    required int? badgeCount,
    required Color color,
  }) {
    final isSelected = _selectedTab == tab;

    return InkWell(
      onTap: () => setState(() => _selectedTab = tab),
      borderRadius: BorderRadius.circular(20),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
        decoration: BoxDecoration(
          color: isSelected ? color : color.withAlpha(20),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: isSelected ? color : color.withAlpha(60),
            width: 1,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              label,
              style: TextStyle(
                fontSize: 12.5,
                fontWeight: isSelected ? FontWeight.bold : FontWeight.w600,
                color: isSelected ? Colors.white : color,
              ),
            ),
            if (badgeCount != null && badgeCount > 0) ...[
              const SizedBox(width: 6),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                decoration: BoxDecoration(
                  color: isSelected ? Colors.white : color,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  '$badgeCount',
                  style: TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.bold,
                    color: isSelected ? color : Colors.white,
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildEmptyState() {
    String message = 'Không có sự cố nào trong mục này.';
    IconData icon = Icons.check_circle_outline_rounded;
    Color iconColor = const Color(0xFF10B981);

    if (_selectedTab == IncidentFilterTab.pending) {
      message = 'Hệ thống an toàn! Hiện tại không có sự cố nào chờ xử lý.';
      icon = Icons.shield_rounded;
      iconColor = const Color(0xFF10B981);
    }

    return Center(
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: iconColor.withAlpha(25),
                ),
                child: Icon(icon, size: 48, color: iconColor),
              ),
              const SizedBox(height: 16),
              Text(
                _selectedTab == IncidentFilterTab.pending ? 'HỆ THỐNG AN TOÀN' : 'Không có sự cố',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: AppColors.txtPrimary(context),
                ),
              ),
              const SizedBox(height: 8),
              Text(
                message,
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 13, color: AppColors.txtSecondary(context)),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildIncidentCard(IncidentModel incident, IncidentProvider provider) {
    final sevColor = _getSeverityColor(incident.severityWeight);
    final isPending = incident.isNew;
    final isClaimed = incident.isClaimed;
    final isResolved = incident.isResolved;
    final isClaimedByMe = isClaimed && incident.claimedById == _currentUserId;
    final timeStr = _formatTime(incident.detectedAt);
    final cleanReason = _cleanDetails(incident.resolutionNotes);

    return Container(
      decoration: BoxDecoration(
        color: AppColors.crd(context),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isPending ? sevColor.withAlpha(80) : AppColors.crdBorder(context),
          width: isPending ? 1.5 : 1,
        ),
        boxShadow: [
          BoxShadow(
            color: isPending ? sevColor.withAlpha(20) : Colors.black.withAlpha(15),
            blurRadius: 10,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: () {
            // Xem chi tiết / xử lý
            if (isClaimedByMe) {
              IncidentDetailSheet.show(
                context: context,
                incident: incident,
                onResolved: () {
                  provider.loadActiveIncidents();
                  _fetchIncidentsHistory();
                },
              );
            } else if (incident.imageUrl != null) {
              _showImagePreview(context, incident.imageUrl!);
            }
          },
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 1. Header: Loại sự cố & Badge trạng thái & Thời gian
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                      decoration: BoxDecoration(
                        color: sevColor.withAlpha(25),
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: sevColor.withAlpha(80)),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.warning_amber_rounded, size: 14, color: sevColor),
                          const SizedBox(width: 4),
                          Text(
                            incident.eventTypeDisplay,
                            style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                              color: sevColor,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    _buildStatusBadge(incident),
                    const Spacer(),
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.access_time_rounded, size: 13, color: AppColors.txtSecondary(context)),
                        const SizedBox(width: 4),
                        Text(
                          timeStr,
                          style: TextStyle(
                            fontSize: 11.5,
                            fontWeight: FontWeight.w500,
                            color: AppColors.txtSecondary(context),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),

                const SizedBox(height: 12),

                // 2. Vị trí & Camera
                Row(
                  children: [
                    const Icon(Icons.location_on_outlined, size: 16, color: AppColors.primaryLight),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        '${incident.areaName ?? 'Chưa rõ khu vực'} • ${incident.cameraCode}',
                        style: TextStyle(
                          fontSize: 13.5,
                          fontWeight: FontWeight.bold,
                          color: AppColors.txtPrimary(context),
                        ),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 6),

                // 3. Nội dung lý do sự cố (súc tích, không mã AI)
                Padding(
                  padding: const EdgeInsets.only(left: 22),
                  child: Text(
                    cleanReason,
                    style: TextStyle(
                      fontSize: 12.5,
                      height: 1.4,
                      color: AppColors.txtSecondary(context),
                    ),
                  ),
                ),

                // 4. Ảnh bằng chứng snapshot (nếu có)
                if (incident.imageUrl != null && incident.imageUrl!.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  InkWell(
                    onTap: () => _showImagePreview(context, incident.imageUrl!),
                    borderRadius: BorderRadius.circular(10),
                    child: Container(
                      height: 110,
                      width: double.infinity,
                      decoration: BoxDecoration(
                        color: Colors.black26,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: AppColors.crdBorder(context)),
                      ),
                      clipBehavior: Clip.antiAlias,
                      child: Stack(
                        fit: StackFit.expand,
                        children: [
                          Image.network(
                            incident.imageUrl!,
                            fit: BoxFit.cover,
                            errorBuilder: (ctx, err, stack) => Center(
                              child: Text(
                                'Ảnh bằng chứng không khả dụng',
                                style: TextStyle(fontSize: 11, color: AppColors.txtSecondary(context)),
                              ),
                            ),
                          ),
                          Positioned(
                            right: 8,
                            bottom: 8,
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                              decoration: BoxDecoration(
                                color: Colors.black.withAlpha(160),
                                borderRadius: BorderRadius.circular(6),
                              ),
                              child: const Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(Icons.zoom_in, color: Colors.white, size: 14),
                                  SizedBox(width: 4),
                                  Text(
                                    'Phóng to ảnh',
                                    style: TextStyle(color: Colors.white, fontSize: 10.5),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],

                // 5. Thông tin xử lý (nếu đang xử lý hoặc đã giải quyết)
                if (isClaimed) ...[
                  const SizedBox(height: 10),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                    decoration: BoxDecoration(
                      color: const Color(0xFF3B82F6).withAlpha(15),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: const Color(0xFF3B82F6).withAlpha(40)),
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.shield_outlined, size: 14, color: Color(0xFF3B82F6)),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            isClaimedByMe
                                ? 'Bạn đang tiếp nhận xử lý sự cố này'
                                : 'Đang xử lý bởi: ${incident.claimedByName ?? 'Bảo vệ khác'}',
                            style: const TextStyle(
                              fontSize: 11.5,
                              color: Color(0xFF3B82F6),
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ] else if (isResolved) ...[
                  const SizedBox(height: 10),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                    decoration: BoxDecoration(
                      color: const Color(0xFF10B981).withAlpha(15),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: const Color(0xFF10B981).withAlpha(40)),
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.check_circle_outline, size: 14, color: Color(0xFF10B981)),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            'Đã giải quyết bởi ${incident.resolvedByName ?? 'Bảo vệ'} (${incident.outcome ?? 'Đã xác minh'})',
                            style: const TextStyle(
                              fontSize: 11.5,
                              color: Color(0xFF10B981),
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],

                const SizedBox(height: 14),

                // 6. Action buttons
                Row(
                  children: [
                    // Nút xem vị trí trên Bản đồ
                    OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppColors.primaryLight,
                        side: BorderSide(color: AppColors.primaryLight.withAlpha(120)),
                        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                        minimumSize: const Size(0, 36),
                        shape: RoundedRectangleApp.rounded(8),
                      ),
                      onPressed: () => _navigateToMap(incident),
                      icon: const Icon(Icons.navigation_outlined, size: 15),
                      label: const Text('Bản đồ', style: TextStyle(fontSize: 12)),
                    ),

                    const Spacer(),

                    // Nút tiếp nhận hoặc giải quyết
                    if (isPending)
                      ElevatedButton.icon(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: sevColor,
                          foregroundColor: Colors.white,
                          elevation: 2,
                          minimumSize: const Size(0, 36),
                          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                          shape: RoundedRectangleApp.rounded(8),
                        ),
                        onPressed: provider.isClaimLoading
                            ? null
                            : () async {
                                final success = await provider.claimIncident(incident.id);
                                if (!mounted) return;
                                if (success) {
                                  ScaffoldMessenger.of(context).showSnackBar(
                                    const SnackBar(
                                      content: Text('Tiếp nhận sự cố thành công!'),
                                      backgroundColor: Color(0xFF10B981),
                                      duration: Duration(seconds: 2),
                                    ),
                                  );
                                  await _fetchIncidentsHistory();
                                }
                              },
                        icon: const Icon(Icons.assignment_turned_in_outlined, size: 15),
                        label: const Text('TIẾP NHẬN', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
                      )
                    else if (isClaimedByMe)
                      ElevatedButton.icon(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: const Color(0xFF10B981),
                          foregroundColor: Colors.white,
                          elevation: 2,
                          minimumSize: const Size(0, 36),
                          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                          shape: RoundedRectangleApp.rounded(8),
                        ),
                        onPressed: () {
                          IncidentDetailSheet.show(
                            context: context,
                            incident: incident,
                            onResolved: () {
                              provider.loadActiveIncidents();
                              _fetchIncidentsHistory();
                            },
                          );
                        },
                        icon: const Icon(Icons.check_circle_outline, size: 15),
                        label: const Text('GIẢI QUYẾT', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
                      ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildStatusBadge(IncidentModel incident) {
    String label = 'Chờ xử lý';
    Color color = const Color(0xFFEF4444);

    if (incident.isClaimed) {
      label = 'Đang xử lý';
      color = const Color(0xFF3B82F6);
    } else if (incident.isResolved) {
      label = 'Đã đóng';
      color = const Color(0xFF10B981);
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withAlpha(20),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: color.withAlpha(50)),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 10.5,
          fontWeight: FontWeight.bold,
          color: color,
        ),
      ),
    );
  }
}

class RoundedRectangleApp {
  static RoundedRectangleBorder rounded(double radius) =>
      RoundedRectangleBorder(borderRadius: BorderRadius.circular(radius));
}
