import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/incident_model.dart';
import '../providers/incident_provider.dart';

class IncidentQueueSheet extends StatelessWidget {
  final Function(IncidentModel) onClaim;
  final Function(IncidentModel) onPreviewOnMap;

  const IncidentQueueSheet({
    super.key,
    required this.onClaim,
    required this.onPreviewOnMap,
  });

  static void show({
    required BuildContext context,
    required Function(IncidentModel) onClaim,
    required Function(IncidentModel) onPreviewOnMap,
  }) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (ctx) => IncidentQueueSheet(
        onClaim: onClaim,
        onPreviewOnMap: onPreviewOnMap,
      ),
    );
  }

  Color _getSeverityColor(int weight) {
    switch (weight) {
      case 3:
        return const Color(0xFFEF4444); // Red
      case 2:
        return const Color(0xFFF59E0B); // Amber / Orange
      case 1:
      default:
        return const Color(0xFF8B5CF6); // Purple / Blue
    }
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<IncidentProvider>();
    final queue = provider.alertQueue;

    return Container(
      constraints: BoxConstraints(
        maxHeight: MediaQuery.of(context).size.height * 0.75,
      ),
      decoration: const BoxDecoration(
        color: Color(0xFF0F172A),
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        border: Border(
          top: BorderSide(color: Color(0xFF334155), width: 1.5),
          left: BorderSide(color: Color(0xFF334155), width: 1.5),
          right: BorderSide(color: Color(0xFF334155), width: 1.5),
        ),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 12),
            // Drag handle
            Center(
              child: Container(
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: Colors.white24,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 14),

            // Header: Tiêu đề và tổng số sự cố
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: const Color(0xFFEF4444).withAlpha(35),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: const Icon(
                      Icons.warning_amber_rounded,
                      color: Color(0xFFEF4444),
                      size: 20,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Hàng Đợi Sự Cố Chờ Xử Lý',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        Text(
                          'Sắp xếp theo mức độ ưu tiên khẩn cấp',
                          style: TextStyle(
                            color: Colors.white.withAlpha(150),
                            fontSize: 11,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    decoration: BoxDecoration(
                      color: const Color(0xFFEF4444),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(
                      '${queue.length} sự cố',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 16),
            const Divider(color: Color(0xFF1E293B), height: 1),

            // Danh sách sự cố
            if (queue.isEmpty)
              Padding(
                padding: const EdgeInsets.all(32),
                child: Center(
                  child: Text(
                    'Hiện không có sự cố nào chờ xử lý.',
                    style: TextStyle(color: Colors.white.withAlpha(140)),
                  ),
                ),
              )
            else
              Flexible(
                child: ListView.separated(
                  shrinkWrap: true,
                  padding: const EdgeInsets.all(16),
                  itemCount: queue.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 12),
                  itemBuilder: (context, index) {
                    final item = queue[index];
                    final color = _getSeverityColor(item.severityWeight);

                    return Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: const Color(0xFF1E293B),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: color.withAlpha(120), width: 1.2),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          // Hàng 1: Badge mức độ & thời gian
                          Row(
                            children: [
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                decoration: BoxDecoration(
                                  color: color.withAlpha(40),
                                  borderRadius: BorderRadius.circular(6),
                                  border: Border.all(color: color.withAlpha(160)),
                                ),
                                child: Text(
                                  item.severityLabel,
                                  style: TextStyle(
                                    color: color,
                                    fontSize: 10,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ),
                              const Spacer(),
                              Icon(Icons.access_time_rounded, size: 12, color: Colors.white.withAlpha(130)),
                              const SizedBox(width: 4),
                              Text(
                                item.detectedAt != null
                                    ? '${item.detectedAt!.hour.toString().padLeft(2, '0')}:${item.detectedAt!.minute.toString().padLeft(2, '0')}:${item.detectedAt!.second.toString().padLeft(2, '0')}'
                                    : 'Vừa xong',
                                style: TextStyle(
                                  color: Colors.white.withAlpha(140),
                                  fontSize: 11,
                                ),
                              ),
                            ],
                          ),

                          const SizedBox(height: 8),

                          // Hàng 2: Tên loại sự cố
                          Text(
                            item.eventTypeDisplay,
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 15,
                              fontWeight: FontWeight.bold,
                            ),
                          ),

                          const SizedBox(height: 4),

                          // Hàng 3: Vị trí Camera & Khu vực
                          Row(
                            children: [
                              const Icon(Icons.videocam_outlined, size: 14, color: Color(0xFF38BDF8)),
                              const SizedBox(width: 4),
                              Text(
                                item.cameraCode,
                                style: const TextStyle(
                                  color: Color(0xFF38BDF8),
                                  fontSize: 12,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              const SizedBox(width: 8),
                              const Text('•', style: TextStyle(color: Colors.white38)),
                              const SizedBox(width: 8),
                              const Icon(Icons.location_on_outlined, size: 14, color: Colors.white70),
                              const SizedBox(width: 4),
                              Expanded(
                                child: Text(
                                  item.areaName ?? 'Chưa rõ khu vực',
                                  style: const TextStyle(
                                    color: Colors.white70,
                                    fontSize: 12,
                                  ),
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            ],
                          ),

                          if (item.resolutionNotes != null && item.resolutionNotes!.isNotEmpty) ...[
                            const SizedBox(height: 6),
                            Text(
                              item.resolutionNotes!,
                              style: TextStyle(
                                color: Colors.white.withAlpha(170),
                                fontSize: 11.5,
                              ),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ],

                          const SizedBox(height: 12),

                          // Hàng 4: 2 Nút thao tác
                          Row(
                            children: [
                              // Nút xem vị trí trên bản đồ
                              Expanded(
                                child: OutlinedButton.icon(
                                  style: OutlinedButton.styleFrom(
                                    foregroundColor: Colors.white70,
                                    side: const BorderSide(color: Color(0xFF475569)),
                                    padding: const EdgeInsets.symmetric(vertical: 8),
                                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                                  ),
                                  onPressed: () {
                                    Navigator.pop(context);
                                    onPreviewOnMap(item);
                                  },
                                  icon: const Icon(Icons.location_searching_rounded, size: 15),
                                  label: const Text('Xem vị trí', style: TextStyle(fontSize: 12)),
                                ),
                              ),
                              const SizedBox(width: 10),
                              // Nút tiếp nhận & dẫn đường ngay
                              Expanded(
                                flex: 1,
                                child: ElevatedButton.icon(
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: const Color(0xFF2563EB),
                                    foregroundColor: Colors.white,
                                    padding: const EdgeInsets.symmetric(vertical: 8),
                                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                                    elevation: 2,
                                  ),
                                  onPressed: () {
                                    Navigator.pop(context);
                                    onClaim(item);
                                  },
                                  icon: const Icon(Icons.navigation_rounded, size: 15),
                                  label: const Text(
                                    'Tiếp nhận & Đi',
                                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    );
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }
}
