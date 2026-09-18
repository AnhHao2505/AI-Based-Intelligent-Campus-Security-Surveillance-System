# Standalone Flutter Guard Mobile App: Schedule & Shift Management

**Date:** 2026-09-16  
**Status:** Approved by User  
**Target:** Dedicated Mobile Application for Patrol Guards (`mobile/`) + Web Cleanup

---

## 1. Executive Summary

This document specifies the architecture and implementation for the standalone **Flutter Guard Mobile App** (`mobile/`), replacing the temporary web-based mobile view (`GuardMobilePage.jsx`). 

Per user direction, the scope of this phase prioritizes **Authentication** and **Guard Shift/Schedule Visualization & Attendance (Check-in/Check-out)** as assigned by the Admin, while reverting the Web console to a pure desktop experience and keeping the Admin Schedule Management intact. Incident alerts and real-time dispatching remain architecturally planned for the subsequent phase.

---

## 2. Web Frontend Cleanup & Scoping

### 2.1 Web Changes
1. **Remove Web Mobile Route:** Remove `/guard/mobile` from `frontend/src/App.jsx`.
2. **Delete Web Mobile Page:** Remove `frontend/src/pages/guard/GuardMobilePage.jsx`.
3. **Revert Header Link:** Remove the `App Cơ Động (Mobile)` button from `frontend/src/pages/guard/GuardDashboardPage.jsx`.
4. **Preserve Desktop Guard Console:** Retain real-time WebSocket listeners (`/topic/incidents/updates`) and video feeds on `GuardDashboardPage.jsx` so that future mobile claims and resolutions display live on PC.
5. **Preserve Admin Schedule Management:** Retain 100% of `/admin/guard-schedules` (`GuardScheduleManagementPage.jsx`) and all backend APIs.

---

## 3. Flutter Mobile Architecture (`mobile/`)

### 3.1 Technology Stack
* **Framework:** Flutter 3.44.0 (Dart 3.12.0)
* **Design System:** Material 3 Dark Tactical Theme (Slate 900 background, Slate 800 cards, Emerald for Check-in, Amber for active shifts, Rose for alerts)
* **State Management:** `provider` (version ^6.1.2)
* **HTTP & REST:** `dio` (version ^5.4.1) with AuthInterceptor for automatic JWT Bearer injection
* **Local Storage:** `shared_preferences` (version ^2.2.2) and `flutter_secure_storage` (version ^9.0.0)
* **Internationalization & Dates:** `intl` (version ^0.19.0)

### 3.2 Directory Structure
```text
mobile/
├── pubspec.yaml
├── lib/
│   ├── main.dart                          # App entry point, MultiProvider setup, theme & routing
│   ├── core/
│   │   ├── constants/
│   │   │   ├── api_endpoints.dart         # Base URL, /api/auth/login, /api/guard-shifts/my-shifts, etc.
│   │   │   ├── app_colors.dart            # Slate-900, Slate-800, Emerald, Amber, Rose, etc.
│   │   │   └── app_theme.dart             # Material 3 dark tactical theme configuration
│   │   ├── network/
│   │   │   └── api_client.dart            # Dio client with Interceptors, Bearer token injection, error handling
│   │   └── utils/
│   │       └── storage_helper.dart        # Secure persistence for token and user profile
│   └── features/
│       ├── auth/
│       │   ├── models/
│       │   │   └── user_model.dart        # Guard user profile (id, email, fullName, role, building)
│       │   ├── providers/
│       │   │   └── auth_provider.dart     # Authentication state, login, logout, auto-restore session
│       │   └── screens/
│       │       └── login_screen.dart      # Tactical Guard Login screen
│       └── shift/
│           ├── models/
│           │   └── guard_shift_model.dart # Shift entity (id, shiftDate, shiftType, startTime, endTime, areaName, radioChannel, status, notes)
│           ├── providers/
│           │   └── shift_provider.dart    # Shifts fetching, date selection, check-in & check-out state
│           ├── widgets/
│           │   ├── weekly_date_bar.dart   # Horizontal 7-day selector (Mon - Sun) with today & active shift badges
│           │   ├── active_shift_card.dart # Highlighted active shift card with live status & Check-in / Check-out buttons
│           │   └── shift_list_item.dart   # Shift item card for weekly schedule list
│           └── screens/
│               └── guard_schedule_screen.dart # Main screen displaying schedule, shifts, attendance, and profile header
```

---

## 4. Feature Specifications

### 4.1 Authentication & Session
* **Endpoint:** `POST /api/auth/login`
  * Body: `{"email": "guard.an@fpt.edu.vn", "password": "..."}`
  * Response: `{"accessToken": "...", "user": {"id": "...", "fullName": "Nguyễn Văn An", "role": "GUARD"}}`
* **Session Persistence:**
  * Stores `accessToken` in secure storage.
  * Restores user profile on app startup.
  * Provides instant Logout with confirmation dialog.

### 4.2 Guard Shift Roster & Calendar
* **API Integration:**
  * Fetch shifts: `GET /api/guard-shifts/my-shifts?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD`
  * Check-in: `POST /api/guard-shifts/{id}/check-in`
  * Check-out: `POST /api/guard-shifts/{id}/check-out`
* **Horizontal Date Bar (`WeeklyDateBar`):**
  * Displays 7 days of the current week.
  * Highlights "Today" with an indicator dot and current date label.
  * Tapping a date filters and highlights the shift for that specific day.
* **Active Shift Card (`ActiveShiftCard`):**
  * Displays shift type with visual cues:
    * `SHIFT_MORNING` (06:00 - 14:00) 🌅 Sky Blue
    * `SHIFT_AFTERNOON` (14:00 - 22:00) 🌇 Amber
    * `SHIFT_NIGHT` (22:00 - 06:00) 🌙 Indigo
    * `SHIFT_OFF` ☕ Slate Grey
  * Displays assigned post/area (e.g., `Phòng Giám Sát Camera`).
  * Displays walkie-talkie channel (e.g., `📻 Kênh 2`).
  * Displays Admin assignment notes.
  * **Attendance Actions:**
    * If `status == 'SCHEDULED'`: Green prominent button `NHẬN CA TRỰC (CHECK-IN)`.
    * If `status == 'CHECKED_IN'`: Orange/Red outline button `KẾT THÚC CA (CHECK-OUT)`.
    * If `status == 'COMPLETED'`: Grey badge `ĐÃ HOÀN THÀNH`.
* **Weekly Schedule List:**
  * Overview of all shifts scheduled for the week by the Admin.
  * Empty state when no shifts are scheduled ("Không có ca trực trong ngày này").

---

## 5. Verification Plan

### 5.1 Automated Checks
1. `flutter analyze` inside `mobile/` ensuring zero warnings or compilation errors.
2. `flutter test` verifying Shift Model serialization, API client parsing, and Provider state changes.
3. `npm.cmd run build` inside `frontend/` verifying the web build succeeds cleanly after removing `/guard/mobile`.

### 5.2 Manual End-to-End Verification
1. Log in to Web as Admin (`admin@fpt.edu.vn`) at `http://localhost:5173/admin/guard-schedules`.
2. Generate weekly shifts for `Nguyễn Văn An` for the current week.
3. Launch Flutter mobile app (on Android emulator or device).
4. Log in as `guard.an@fpt.edu.vn`.
5. Verify the weekly schedule displays identical shifts to what the Admin configured.
6. Tap `NHẬN CA TRỰC (CHECK-IN)`. Verify status changes to `CHECKED_IN` both on Mobile and on Admin Web.
7. Tap `KẾT THÚC CA (CHECK-OUT)`. Verify status updates to `COMPLETED`.
