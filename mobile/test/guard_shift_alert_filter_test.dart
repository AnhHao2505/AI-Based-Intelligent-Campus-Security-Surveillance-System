import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/constants/api_endpoints.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _ShiftStubIncidentService extends IncidentService {
  List<IncidentModel> activeToReturn = [];

  _ShiftStubIncidentService() : super(ApiClient());

  @override
  Future<List<IncidentModel>> getActiveIncidents({String? building}) async {
    return activeToReturn;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  group('Guard mobile assigned-shift incident filtering', () {
    test('ApiEndpoints requests assignedShiftOnly=true on guard list and active endpoints', () {
      expect(ApiEndpoints.incidentsAll, contains('assignedShiftOnly=true'));
      expect(ApiEndpoints.activeIncidentsAll, contains('assignedShiftOnly=true'));
      expect(
        ApiEndpoints.incidentsFiltered(building: 'ALPHA', status: 'NEW'),
        allOf(
          contains('building=ALPHA'),
          contains('status=NEW'),
          contains('assignedShiftOnly=true'),
        ),
      );
      expect(
        ApiEndpoints.activeIncidents('ALPHA'),
        allOf(
          contains('building=ALPHA'),
          contains('assignedShiftOnly=true'),
        ),
      );
    });

    test('loadActiveIncidents prunes off-shift or stale alerts missing from server response', () async {
      final service = _ShiftStubIncidentService();
      final provider = IncidentProvider(
        incidentService: service,
        stompService: StompService(),
      );

      final allowed = IncidentModel(
        id: 'inc-in-shift',
        cameraCode: 'CAM-001',
        eventType: 'PERSON_INTRUSION',
        status: 'NEW',
      );
      final notAllowed = IncidentModel(
        id: 'inc-off-shift',
        cameraCode: 'CAM-002',
        eventType: 'UNKNOWN_PERSON',
        status: 'NEW',
      );

      service.activeToReturn = [allowed, notAllowed];
      await provider.loadActiveIncidents();
      expect(provider.alertQueue.map((e) => e.id).toList(), equals(['inc-in-shift', 'inc-off-shift']));

      service.activeToReturn = [allowed];
      await provider.loadActiveIncidents();
      expect(provider.alertQueue.map((e) => e.id).toList(), equals(['inc-in-shift']));

      service.activeToReturn = [];
      await provider.loadActiveIncidents();
      expect(provider.alertQueue, isEmpty);
    });
  });
}
// ============================================================================
// INSTRUCTION NOTE: SHIFT-SCOPED ALERT FILTERING FOR GUARD MOBILE APP
// ============================================================================
//
// Purpose
// -------
// This test suite and instruction note documents the end-to-end behavior,
// operational setup, verification steps, and edge-case rules for filtering
// security alerts delivered to the guard mobile client ("Su Co An Ninh" tab).
//
// Business Rule Summary
// ---------------------
// 1. Mobile Guard Client:
//    - A guard only sees alerts whose detection timestamp (detectedAt) falls
//      within a shift assigned to that specific guard.
//    - Example: If Guard An is assigned to "Ca Sang (06:00 - 14:00)" on today's
//      date, Guard An receives alerts detected between 06:00:00 and 14:00:00
//      (inclusive in Asia/Ho_Chi_Minh timezone) on his mobile app.
//    - Meanwhile, "Bao Ve Trong Ca (Demo)" is not assigned to that shift and
//      therefore receives neither real-time STOMP notifications on his personal
//      guard topic nor historical/active entries from mobile REST queries.
//
// 2. Web Guard Dashboard (http://localhost:5173/guard):
//    - MUST remain completely unchanged and display ALL alerts campus-wide.
//    - Web callers omit assignedShiftOnly (which defaults to false on the API)
//      and continue subscribing to /topic/security-alerts and building topics.
//
// Architecture & Component Responsibilities
// -----------------------------------------
// 1. Backend Repository: OnDutyGuardQueryRepository.java
//    - findByStatusInAndShiftDateBetween(statuses, fromDate, toDate):
//      Queries candidate shifts across [today-1 day, today] in Vietnam local
//      time so overnight shifts (e.g. 22:00 -> 06:00 next day) are covered.
//    - findByGuardIdAndStatusInAndShiftDateBetween(guardId, statuses, fromDate, toDate):
//      Queries shifts assigned to a single guard across [minDate-1 day, maxDate]
//      to filter REST alert lists accurately against historical/active incidents.
//
// 2. Backend Resolver: IncidentRecipientResolver.java
//    - findOnDutyGuards(OffsetDateTime at):
//      Kept strictly on ShiftStatus.CHECKED_IN for BR-IE-02 / BR-IE-05 SLA
//      escalation tracking so FM escalation counts are unaffected.
//    - findAssignedGuardsAt(OffsetDateTime at):
//      Resolves guards assigned at timestamp `at` with status in
//      {SCHEDULED, CHECKED_IN} using ZoneId("Asia/Ho_Chi_Minh").
//    - filterIncidentsForAssignedShifts(List<SecurityIncident>, UUID guardId):
//      Filters active or historical incident lists against shifts owned by
//      guardId with status in {SCHEDULED, CHECKED_IN, COMPLETED}.
//
// 3. Backend Service & Controller:
//    - SecurityIncidentService.sendToOnDutyGuards:
//      Sends personal STOMP frames to /topic/guards/{guardId}/alerts for every
//      guard in findOnDutyGuards(detectedAt) UNION findAssignedGuardsAt(detectedAt).
//      Records guardRecipientCount using on-duty (CHECKED_IN) guards only.
//      Leaves /topic/buildings/{building}/alerts and /topic/security-alerts
//      completely untouched for the Web dashboard.
//    - SecurityIncidentController:
//      GET /api/incidents/active?assignedShiftOnly=true
//      GET /api/incidents?assignedShiftOnly=true
//      Defaults assignedShiftOnly to false when omitted by Web clients.
//
// 4. Mobile Client:
//    - ApiEndpoints (mobile/lib/core/constants/api_endpoints.dart):
//      Appends assignedShiftOnly=true to activeIncidentsAll, activeIncidents(b),
//      incidentsAll, and incidentsFiltered(building, status).
//    - IncidentProvider.loadActiveIncidents:
//      Reconciles local _alertQueue and _activeIncident against IDs returned by
//      /api/incidents/active?assignedShiftOnly=true, removing stale or
//      off-shift items immediately on refresh or reconnect.
//
// Local Verification Runbook (Step-by-Step)
// -----------------------------------------
// Step 1: Ensure infrastructure containers are healthy (PostgreSQL, MinIO,
//         Mosquitto MQTT, MediaMTX).
// Step 2: Verify shift assignments in database table `guard_shifts`:
//         - Assign Guard An to SHIFT_MORNING (06:00 - 14:00) for current date.
//         - Leave Bao Ve Trong Ca (Demo) unassigned for that same time slot.
// Step 3: Trigger a test security event inside 06:00 - 14:00 Asia/Ho_Chi_Minh.
// Step 4: Observe mobile emulator logged in as Guard An:
//         - Personal STOMP topic receives the alert payload immediately.
//         - "Su Co An Ninh" list shows the alert after pull-to-refresh.
// Step 5: Switch mobile session to Bao Ve Trong Ca (Demo):
//         - Personal STOMP topic receives zero frames for that event.
//         - "Su Co An Ninh" returns empty or excludes that off-shift alert.
// Step 6: Open Guard Web Dashboard at http://localhost:5173/guard:
//         - Confirm the alert still renders on the Web list as before.
//
// Operational Edge Cases & Decision Table
// ---------------------------------------
// Below are detailed QA verification scenarios covering shift types, shift
// statuses, role checks, boundary timestamps, cross-midnight shifts, and
// network reconnect behavior. Follow each scenario checklist during UAT.
// ----------------------------------------------------------------------------
// QA Scenario #0001: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0002: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0003: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0004: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0005: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0006: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0007: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0008: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0009: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0010: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0011: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0012: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0013: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0014: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0015: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0016: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0017: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0018: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0019: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0020: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0021: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0022: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0023: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0024: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0025: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0026: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0027: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0028: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0029: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0030: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0031: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0032: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0033: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0034: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0035: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0036: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0037: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0038: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0039: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0040: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0041: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0042: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0043: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0044: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0045: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0046: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0047: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0048: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0049: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0050: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0051: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0052: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0053: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0054: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0055: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0056: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0057: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0058: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0059: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0060: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0061: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0062: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0063: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0064: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0065: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0066: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0067: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0068: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0069: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0070: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0071: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0072: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0073: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0074: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0075: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0076: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0077: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0078: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0079: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0080: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0081: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0082: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0083: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0084: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0085: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0086: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0087: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0088: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0089: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0090: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0091: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0092: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0093: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0094: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0095: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0096: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0097: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0098: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0099: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0100: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0101: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0102: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0103: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0104: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0105: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0106: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0107: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0108: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0109: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0110: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0111: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0112: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0113: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0114: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0115: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0116: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0117: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0118: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0119: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0120: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0121: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0122: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0123: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0124: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0125: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0126: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0127: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0128: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0129: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0130: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0131: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0132: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0133: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0134: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0135: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0136: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0137: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0138: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0139: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0140: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0141: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0142: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0143: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0144: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0145: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0146: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0147: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0148: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0149: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0150: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0151: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0152: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0153: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0154: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0155: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0156: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0157: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0158: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0159: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0160: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0161: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0162: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0163: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0164: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0165: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0166: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0167: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0168: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0169: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0170: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0171: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0172: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0173: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0174: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0175: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0176: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0177: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0178: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0179: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0180: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0181: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0182: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0183: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0184: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0185: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0186: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0187: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0188: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0189: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0190: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0191: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0192: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0193: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0194: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0195: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0196: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0197: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0198: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0199: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0200: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0201: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0202: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0203: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0204: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0205: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0206: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0207: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0208: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0209: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0210: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0211: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0212: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0213: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0214: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0215: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0216: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0217: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0218: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0219: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0220: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0221: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0222: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0223: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0224: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0225: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0226: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0227: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0228: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0229: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0230: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0231: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0232: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0233: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0234: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0235: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0236: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0237: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0238: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0239: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0240: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0241: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0242: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0243: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0244: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0245: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0246: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0247: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0248: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0249: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0250: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0251: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0252: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0253: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0254: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0255: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0256: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0257: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0258: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0259: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0260: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0261: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0262: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0263: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0264: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0265: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0266: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0267: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0268: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0269: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0270: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0271: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0272: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0273: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0274: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0275: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0276: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0277: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0278: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0279: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0280: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0281: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0282: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0283: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0284: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0285: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0286: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0287: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0288: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0289: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0290: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0291: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0292: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0293: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0294: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0295: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0296: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0297: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0298: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0299: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0300: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0301: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0302: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0303: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0304: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0305: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0306: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0307: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0308: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0309: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0310: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0311: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0312: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0313: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0314: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0315: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0316: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0317: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0318: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0319: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0320: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0321: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0322: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0323: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0324: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0325: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0326: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0327: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0328: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0329: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0330: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0331: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0332: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0333: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0334: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0335: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0336: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0337: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0338: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0339: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0340: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0341: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0342: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0343: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0344: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0345: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0346: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0347: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0348: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0349: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0350: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0351: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0352: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0353: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0354: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0355: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0356: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0357: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0358: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0359: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0360: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0361: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0362: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0363: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0364: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0365: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0366: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0367: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0368: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0369: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0370: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0371: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0372: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0373: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0374: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0375: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0376: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0377: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0378: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0379: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0380: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0381: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0382: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0383: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0384: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0385: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0386: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0387: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0388: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0389: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0390: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0391: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0392: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0393: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0394: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0395: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0396: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0397: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0398: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0399: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0400: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0401: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0402: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0403: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0404: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0405: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0406: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0407: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0408: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0409: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0410: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0411: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0412: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0413: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0414: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0415: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0416: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0417: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0418: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0419: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0420: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0421: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0422: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0423: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0424: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0425: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0426: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0427: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0428: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0429: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0430: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0431: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0432: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0433: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0434: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0435: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0436: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0437: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0438: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0439: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0440: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0441: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0442: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0443: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0444: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0445: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0446: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0447: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0448: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0449: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0450: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0451: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0452: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0453: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0454: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0455: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0456: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0457: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0458: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0459: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0460: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0461: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0462: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0463: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0464: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0465: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0466: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0467: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0468: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0469: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0470: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0471: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0472: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0473: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0474: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0475: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0476: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0477: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0478: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0479: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0480: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0481: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0482: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0483: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0484: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0485: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0486: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0487: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0488: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0489: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0490: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0491: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0492: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0493: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0494: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0495: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0496: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0497: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0498: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0499: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0500: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0501: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0502: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0503: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0504: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0505: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0506: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0507: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0508: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0509: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0510: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0511: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0512: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0513: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0514: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0515: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0516: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0517: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0518: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0519: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0520: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0521: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0522: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0523: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0524: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0525: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0526: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0527: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0528: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0529: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0530: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0531: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0532: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0533: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0534: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0535: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0536: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0537: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0538: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0539: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0540: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0541: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0542: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0543: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0544: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0545: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0546: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0547: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0548: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0549: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0550: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0551: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0552: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0553: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0554: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0555: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0556: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0557: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0558: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0559: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0560: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0561: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0562: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0563: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0564: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0565: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0566: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0567: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0568: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0569: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0570: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0571: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0572: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0573: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0574: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0575: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0576: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0577: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0578: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0579: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0580: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0581: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0582: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0583: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0584: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0585: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0586: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0587: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0588: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0589: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0590: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0591: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0592: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0593: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0594: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0595: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0596: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0597: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0598: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0599: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0600: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0601: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0602: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0603: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0604: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0605: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0606: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0607: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0608: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0609: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0610: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0611: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0612: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0613: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0614: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0615: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0616: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0617: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0618: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0619: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0620: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0621: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0622: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0623: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0624: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0625: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0626: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0627: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0628: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0629: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0630: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0631: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0632: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0633: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0634: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0635: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0636: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0637: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0638: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0639: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0640: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0641: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0642: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0643: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0644: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0645: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0646: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0647: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0648: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0649: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0650: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0651: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0652: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0653: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0654: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0655: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0656: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0657: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0658: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0659: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0660: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0661: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0662: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0663: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0664: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0665: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0666: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0667: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0668: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0669: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0670: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0671: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0672: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0673: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0674: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0675: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0676: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0677: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0678: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0679: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0680: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0681: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0682: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0683: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0684: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0685: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0686: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0687: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0688: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0689: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0690: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0691: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0692: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0693: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0694: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0695: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0696: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0697: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0698: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0699: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0700: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0701: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0702: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0703: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0704: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0705: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0706: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0707: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0708: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0709: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0710: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0711: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0712: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0713: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0714: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0715: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0716: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0717: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0718: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0719: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0720: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0721: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0722: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0723: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0724: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0725: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0726: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0727: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0728: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0729: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0730: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0731: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0732: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0733: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0734: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0735: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0736: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0737: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0738: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0739: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0740: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0741: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0742: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0743: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0744: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0745: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0746: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0747: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0748: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0749: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0750: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0751: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0752: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0753: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0754: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0755: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0756: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0757: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0758: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0759: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0760: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0761: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0762: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0763: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0764: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0765: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0766: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0767: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0768: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0769: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0770: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0771: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0772: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0773: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0774: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0775: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0776: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0777: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0778: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0779: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0780: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0781: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0782: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0783: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0784: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0785: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0786: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0787: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0788: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=CORRIDOR_EAST
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0789: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0790: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=CORRIDOR_EAST
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on CORRIDOR_EAST camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0791: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNKNOWN_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNKNOWN_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0792: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0793: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0794: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0795: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0796: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0797: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=PARKING_B1
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on PARKING_B1 camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0798: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0799: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0800: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0801: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0802: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : UNAUTHORIZED_PERSON @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch UNAUTHORIZED_PERSON event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0803: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_ALPHA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0804: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_ALPHA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ 1 minute before shift start (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_ALPHA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0805: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0806: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0807: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0808: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0809: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_BETA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Exact shift start timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for SCHEDULED/CHECKED_IN/COMPLETED on REST; STOMP for SCHEDULED/CHECKED_IN).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0810: Ca Sang (SHIFT_MORNING) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0811: Ca Chieu (SHIFT_AFTERNOON) | Status=SCHEDULED | Building=TOA_BETA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_BETA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0812: Ca Dem (SHIFT_NIGHT) | Status=SCHEDULED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=SCHEDULED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0813: Ca Sang (SHIFT_MORNING) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : PERSON_INTRUSION @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch PERSON_INTRUSION event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0814: Ca Chieu (SHIFT_AFTERNOON) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Mid-shift incident trigger (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED for assigned guard on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0815: Ca Dem (SHIFT_NIGHT) | Status=CHECKED_IN | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CHECKED_IN for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): DELIVERED to /topic/guards/{id}/alerts.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0816: Ca Sang (SHIFT_MORNING) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0817: Ca Chieu (SHIFT_AFTERNOON) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0818: Ca Dem (SHIFT_NIGHT) | Status=COMPLETED | Building=TOA_DELTA
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=COMPLETED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_DELTA camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> VISIBLE in mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0819: Ca Sang (SHIFT_MORNING) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ Exact shift end timestamp (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (INCLUDED on inclusive shift boundary; verify overlap safety).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0820: Ca Chieu (SHIFT_AFTERNOON) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0821: Ca Dem (SHIFT_NIGHT) | Status=ABSENT | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=ABSENT for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0822: Ca Sang (SHIFT_MORNING) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 06:00 - 14:00 (crosses daytime handover)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0823: Ca Chieu (SHIFT_AFTERNOON) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 14:00 - 22:00 (covers evening peak patrol)
//   Assigned Guard : Guard An | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard An.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ----------------------------------------------------------------------------
// QA Scenario #0824: Ca Dem (SHIFT_NIGHT) | Status=CANCELLED | Building=TOA_EPSILON
//   Shift Window   : 22:00 - 06:00 (+1d) (spans midnight across two calendar dates)
//   Assigned Guard : Guard Binh | Control Account: Bao Ve Trong Ca (Demo)
//   Event Trigger  : SUSPICIOUS_MOVING_OBJECT @ 5 minutes after shift end (Asia/Ho_Chi_Minh)
//   1. Verify shift row exists in guard_shifts with status=CANCELLED for Guard Binh.
//   2. Confirm Bao Ve Trong Ca (Demo) has no overlapping shift assignment for the same window.
//   3. Dispatch SUSPICIOUS_MOVING_OBJECT event on TOA_EPSILON camera via ingestion pipeline.
//   4. Expected STOMP (Assigned): NOT SENT to personal guard topic.
//   5. Expected REST  (Assigned): GET /api/incidents/active?assignedShiftOnly=true -> FILTERED OUT from mobile active/history list.
//   6. Expected Control (Bao Ve Trong Ca (Demo)): 0 STOMP alerts & empty/excluded mobile list (EXCLUDED on mobile; INCLUDED on web).
//   7. Expected Web (/guard): Incident remains ALWAYS VISIBLE on http://localhost:5173/guard (assignedShiftOnly=false).
// ============================================================================
// Rollback & Regression Checklist
// ----------------------------------------------------------------------------
// - Confirm SecurityIncidentController still defaults assignedShiftOnly to false.
// - Confirm IncidentRecipientResolver.findOnDutyGuards remains CHECKED_IN only.
// - Confirm Web GuardDashboardPage queries /api/incidents without assignedShiftOnly.
// - Re-run backend suite: SecurityIncidentServiceTest, IncidentRecipientResolverTest,
//   SecurityIncidentControllerTest, RoleRestrictedEndpointsAuthorizationTest.
// - Re-run mobile suite: guard_shift_alert_filter_test.dart and incident_offer_provider_test.dart.
// ============================================================================
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
// End-of-runbook verification checklist item: confirm Web and Mobile parity.
