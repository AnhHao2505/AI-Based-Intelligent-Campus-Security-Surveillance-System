import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/incident_model.dart';
import '../providers/incident_provider.dart';

class IncidentDetailSheet extends StatefulWidget {
  final IncidentModel incident;
  final VoidCallback onResolved;
  final VoidCallback? onCancelNavigation;

  const IncidentDetailSheet({
    super.key,
    required this.incident,
    required this.onResolved,
    this.onCancelNavigation,
  });

  @override
  State<IncidentDetailSheet> createState() => _IncidentDetailSheetState();
}

class _IncidentDetailSheetState extends State<IncidentDetailSheet> {
  bool _isExpanded = false;
  String _outcome = 'VERIFIED';
  String _category = 'REMINDED_DISPERSED';
  final TextEditingController _notesController = TextEditingController();

  final Map<String, String> _categoryLabels = {
    'REMINDED_DISPERSED': 'Nhắc nhở & giải tán',
    'ESCORTED_OUT': 'Áp tải ra khỏi khu vực',
    'REPORT_FILED': 'Lập biên bản vi phạm',
    'DETAINED_ESCALATED': 'Tạm giữ / Báo cấp trên',
    'FALSE_ALARM': 'Báo động giả / Nhầm lẫn',
    'OTHER': 'Khác',
  };

  @override
  void dispose() {
    _notesController.dispose();
    super.dispose();
  }

  void _submitResolution(BuildContext context) async {
    final provider = context.read<IncidentProvider>();
    final messenger = ScaffoldMessenger.of(context);
    final success = await provider.resolveIncident(
      outcome: _outcome,
      resolutionCategory: _category,
      resolutionNotes: _notesController.text.trim().isNotEmpty
          ? _notesController.text.trim()
          : null,
    );

    if (!mounted) return;

    if (success) {
      widget.onResolved();
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Đã hoàn tất xử lý và đóng sự cố thành công.'),
          backgroundColor: Color(0xFF10B981),
        ),
      );
    } else if (provider.errorMessage != null) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(provider.errorMessage!),
          backgroundColor: const Color(0xFFEF4444),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<IncidentProvider>();

    return Positioned(
      bottom: 0,
      left: 0,
      right: 0,
      child: Material(
        elevation: 16,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
        color: const Color(0xFF0F172A),
        child: Container(
          decoration: BoxDecoration(
            borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
            border: Border.all(color: const Color(0xFF334155), width: 1.5),
          ),
          padding: EdgeInsets.only(
            left: 16,
            right: 16,
            top: 12,
            bottom: MediaQuery.of(context).viewInsets.bottom + 12,
          ),
          constraints: BoxConstraints(
            maxHeight: MediaQuery.of(context).size.height * 0.85,
          ),
          child: SingleChildScrollView(
            physics: const ClampingScrollPhysics(),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // Drag handle
                Center(
                  child: Container(
                    width: 36,
                    height: 4,
                    decoration: BoxDecoration(
                      color: const Color(0xFF64748B),
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
              const SizedBox(height: 8),

              // Title Header & Collapse Toggle
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: const Color(0xFFEF4444).withAlpha(51),
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(
                      Icons.shield_outlined,
                      color: Color(0xFFEF4444),
                      size: 20,
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.incident.eventTypeDisplay,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 15,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        Text(
                          '${widget.incident.cameraCode} • ${widget.incident.areaName ?? "Khu vực mục tiêu"}',
                          style: const TextStyle(
                            color: Colors.white70,
                            fontSize: 13,
                          ),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    icon: Icon(
                      _isExpanded
                          ? Icons.keyboard_arrow_down
                          : Icons.keyboard_arrow_up,
                      color: Colors.white70,
                    ),
                    onPressed: () {
                      setState(() {
                        _isExpanded = !_isExpanded;
                      });
                    },
                  ),
                ],
              ),

              // Expanded Resolution Form
              if (_isExpanded) ...[
                const Divider(color: Color(0xFF334155), height: 20),
                const Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    'BÁO CÁO XỬ LÝ HIỆN TRƯỜNG',
                    style: TextStyle(
                      color: Color(0xFF94A3B8),
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0.8,
                    ),
                  ),
                ),
                const SizedBox(height: 10),

                // Outcome Selection
                Row(
                  children: [
                    Expanded(
                      child: ChoiceChip(
                        label: const Center(
                          child: FittedBox(
                            fit: BoxFit.scaleDown,
                            child: Text('Có sự cố (Xác thực)'),
                          ),
                        ),
                        selected: _outcome == 'VERIFIED',
                        backgroundColor: const Color(0xFF1E293B),
                        selectedColor: const Color(0xFFDC2626),
                        side: BorderSide(
                          color: _outcome == 'VERIFIED'
                              ? const Color(0xFFDC2626)
                              : const Color(0xFF475569),
                        ),
                        labelStyle: TextStyle(
                          color: _outcome == 'VERIFIED'
                              ? Colors.white
                              : const Color(0xFFCBD5E1),
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                        onSelected: (val) {
                          if (val) setState(() => _outcome = 'VERIFIED');
                        },
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: ChoiceChip(
                        label: const Center(
                          child: FittedBox(
                            fit: BoxFit.scaleDown,
                            child: Text('Báo động giả / Nhầm'),
                          ),
                        ),
                        selected: _outcome == 'DISMISSED',
                        backgroundColor: const Color(0xFF1E293B),
                        selectedColor: const Color(0xFF475569),
                        side: BorderSide(
                          color: _outcome == 'DISMISSED'
                              ? const Color(0xFF64748B)
                              : const Color(0xFF475569),
                        ),
                        labelStyle: TextStyle(
                          color: _outcome == 'DISMISSED'
                              ? Colors.white
                              : const Color(0xFFCBD5E1),
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                        onSelected: (val) {
                          if (val) {
                            setState(() {
                              _outcome = 'DISMISSED';
                              _category = 'FALSE_ALARM';
                            });
                          }
                        },
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 10),

                // Resolution Category Dropdown
                DropdownButtonFormField<String>(
                  initialValue: _category,
                  dropdownColor: const Color(0xFF1E293B),
                  style: const TextStyle(color: Colors.white, fontSize: 13),
                  iconEnabledColor: const Color(0xFF94A3B8),
                  decoration: InputDecoration(
                    labelText: 'Biện pháp xử lý',
                    labelStyle: const TextStyle(
                      color: Color(0xFF94A3B8),
                      fontSize: 13,
                    ),
                    floatingLabelStyle: const TextStyle(
                      color: Color(0xFF38BDF8),
                      fontSize: 13,
                    ),
                    filled: true,
                    fillColor: const Color(0xFF1E293B),
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
                    enabledBorder: OutlineInputBorder(
                      borderSide: const BorderSide(color: Color(0xFF475569)),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    focusedBorder: OutlineInputBorder(
                      borderSide: const BorderSide(color: Color(0xFF38BDF8)),
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                  items: _categoryLabels.entries.map((e) {
                    return DropdownMenuItem<String>(
                      value: e.key,
                      child: Text(
                        e.value,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 13,
                        ),
                      ),
                    );
                  }).toList(),
                  onChanged: (val) {
                    if (val != null) setState(() => _category = val);
                  },
                ),
                const SizedBox(height: 10),

                // Notes input
                TextField(
                  controller: _notesController,
                  maxLines: 2,
                  style: const TextStyle(color: Colors.white, fontSize: 13),
                  cursorColor: const Color(0xFF38BDF8),
                  decoration: InputDecoration(
                    hintText: 'Ghi chú hiện trường (tùy chọn)...',
                    hintStyle: const TextStyle(
                      color: Color(0xFF64748B),
                      fontSize: 13,
                    ),
                    filled: true,
                    fillColor: const Color(0xFF1E293B),
                    contentPadding: const EdgeInsets.all(12),
                    enabledBorder: OutlineInputBorder(
                      borderSide: const BorderSide(color: Color(0xFF475569)),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    focusedBorder: OutlineInputBorder(
                      borderSide: const BorderSide(color: Color(0xFF38BDF8)),
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
              ],

              // Actions Row
              Row(
                children: [
                  if (!_isExpanded)
                    Expanded(
                      child: OutlinedButton(
                        onPressed: () {
                          setState(() {
                            _isExpanded = true;
                          });
                        },
                        style: OutlinedButton.styleFrom(
                          foregroundColor: Colors.white,
                          backgroundColor: const Color(0xFF1E293B),
                          side: const BorderSide(color: Color(0xFF475569)),
                          padding: const EdgeInsets.symmetric(vertical: 11),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(8),
                          ),
                        ),
                        child: const Text(
                          'Báo cáo xử lý',
                          style: TextStyle(fontWeight: FontWeight.w600),
                        ),
                      ),
                    ),
                  if (_isExpanded) ...[
                    Expanded(
                      flex: 2,
                      child: OutlinedButton(
                        onPressed: () {
                          setState(() {
                            _isExpanded = false;
                          });
                        },
                        style: OutlinedButton.styleFrom(
                          foregroundColor: Colors.white,
                          backgroundColor: const Color(0xFF1E293B),
                          side: const BorderSide(color: Color(0xFF475569)),
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(8),
                          ),
                        ),
                        child: const Text(
                          'Thu gọn',
                          style: TextStyle(fontWeight: FontWeight.w600),
                        ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      flex: 3,
                      child: ElevatedButton.icon(
                        onPressed: provider.isResolveLoading
                            ? null
                            : () => _submitResolution(context),
                        icon: provider.isResolveLoading
                            ? const SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: Colors.white,
                                ),
                              )
                            : const Icon(Icons.check_circle_outline, size: 18),
                        label: Text(
                          provider.isResolveLoading
                              ? 'Đang gửi...'
                              : 'Xử lý xong',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: const Color(0xFF10B981),
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(8),
                          ),
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
}
