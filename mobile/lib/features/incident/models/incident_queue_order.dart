import 'incident_model.dart';

/// Thứ tự hiển thị sự cố, dùng chung cho hàng đợi (IncidentProvider) và màn danh sách (L12, Lucas chốt 07/11):
/// 1. mức server (severityLevel, thiếu thì theo trọng số nhãn) giảm dần;
/// 2. trọng số nhãn (severityWeight) giảm dần;
/// 3. cùng mức, cùng trọng số: phát hiện TRƯỚC đứng trước (FIFO). Sự cố không có detectedAt đứng sau.
int compareIncidentQueueOrder(IncidentModel a, IncidentModel b) {
  final serverCompare = b.severityLevel.compareTo(a.severityLevel);
  if (serverCompare != 0) return serverCompare;
  final sevCompare = b.severityWeight.compareTo(a.severityWeight);
  if (sevCompare != 0) return sevCompare;

  final aTime = a.detectedAt;
  final bTime = b.detectedAt;
  if (aTime == null && bTime == null) return 0;
  if (aTime == null) return 1;
  if (bTime == null) return -1;
  return aTime.compareTo(bTime);
}
