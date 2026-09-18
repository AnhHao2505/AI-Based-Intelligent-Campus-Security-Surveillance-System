import 'package:flutter/foundation.dart';
import 'package:intl/intl.dart';
import '../../../core/constants/api_endpoints.dart';
import '../../../core/network/api_client.dart';
import '../models/guard_shift_model.dart';

class ShiftProvider with ChangeNotifier {
  final ApiClient _apiClient;

  List<GuardShiftModel> _shifts = [];
  DateTime _selectedDate = DateTime.now();
  bool _isLoading = false;
  String? _errorMessage;

  ShiftProvider(this._apiClient);

  List<GuardShiftModel> get shifts => _shifts;
  DateTime get selectedDate => _selectedDate;
  bool get isLoading => _isLoading;
  String? get errorMessage => _errorMessage;

  // Selected date formatted: "yyyy-MM-dd"
  String get selectedDateFormatted => DateFormat('yyyy-MM-dd').format(_selectedDate);

  // Today formatted: "yyyy-MM-dd"
  String get todayFormatted => DateFormat('yyyy-MM-dd').format(DateTime.now());

  // Find all shifts for selected date, sorted by startTime
  List<GuardShiftModel> get shiftsForSelectedDate {
    final target = selectedDateFormatted;
    final list = _shifts.where((s) => s.shiftDate == target).toList();
    list.sort((a, b) => a.startTime.compareTo(b.startTime));
    return list;
  }

  // Find shift for selected date (prefers active or scheduled shift)
  GuardShiftModel? get shiftForSelectedDate {
    final list = shiftsForSelectedDate;
    if (list.isEmpty) return null;
    return list.firstWhere(
      (s) => s.isCheckedIn || s.isScheduled,
      orElse: () => list.first,
    );
  }

  // Find shift for today
  GuardShiftModel? get todayShift {
    final target = todayFormatted;
    final list = _shifts.where((s) => s.shiftDate == target).toList();
    list.sort((a, b) => a.startTime.compareTo(b.startTime));
    if (list.isEmpty) return null;
    return list.firstWhere(
      (s) => s.isCheckedIn || s.isScheduled,
      orElse: () => list.first,
    );
  }

  // Change selected date
  void setSelectedDate(DateTime date) {
    _selectedDate = date;
    notifyListeners();
  }

  // Fetch shifts for the entire week of the current or given date
  Future<void> fetchWeeklyShifts([DateTime? referenceDate]) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final ref = referenceDate ?? _selectedDate;
      // Find Monday (weekday = 1) of the reference week
      final monday = ref.subtract(Duration(days: ref.weekday - 1));
      final sunday = monday.add(const Duration(days: 6));

      final startStr = DateFormat('yyyy-MM-dd').format(monday);
      final endStr = DateFormat('yyyy-MM-dd').format(sunday);

      final response = await _apiClient.dio.get(
        ApiEndpoints.myShifts,
        queryParameters: {
          'startDate': startStr,
          'endDate': endStr,
        },
      );

      if (response.data is List) {
        _shifts = (response.data as List)
            .map((item) => GuardShiftModel.fromJson(item as Map<String, dynamic>))
            .toList();
      } else {
        _shifts = [];
      }

      _errorMessage = null;
    } catch (e) {
      _errorMessage = ApiClient.formatError(e);
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  // Check-in: returns null on success, or error message on failure
  Future<String?> checkIn(String shiftId) async {
    _isLoading = true;
    notifyListeners();

    try {
      final response = await _apiClient.dio.post(ApiEndpoints.checkIn(shiftId));
      if (response.data != null) {
        final updated = GuardShiftModel.fromJson(response.data as Map<String, dynamic>);
        _replaceShift(updated);
      }
      _isLoading = false;
      notifyListeners();
      return null;
    } catch (e) {
      final err = ApiClient.formatError(e);
      _isLoading = false;
      notifyListeners();
      return err;
    }
  }

  // Check-out: returns null on success, or error message on failure
  Future<String?> checkOut(String shiftId) async {
    _isLoading = true;
    notifyListeners();

    try {
      final response = await _apiClient.dio.post(ApiEndpoints.checkOut(shiftId));
      if (response.data != null) {
        final updated = GuardShiftModel.fromJson(response.data as Map<String, dynamic>);
        _replaceShift(updated);
      }
      _isLoading = false;
      notifyListeners();
      return null;
    } catch (e) {
      final err = ApiClient.formatError(e);
      _isLoading = false;
      notifyListeners();
      return err;
    }
  }

  void _replaceShift(GuardShiftModel updated) {
    final index = _shifts.indexWhere((s) => s.id == updated.id);
    if (index != -1) {
      _shifts[index] = updated;
    } else {
      _shifts.add(updated);
    }
  }

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }
}
