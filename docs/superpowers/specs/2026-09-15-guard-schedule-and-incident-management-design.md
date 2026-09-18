# Guard Schedule Management & Real-Time Incident Dispatching
## Technical Design Specification

- **Date:** 2026-09-15
- **Status:** Approved
- **Scope:** Full-stack Module (Flyway Migration, Spring Boot Backend, WebSocket STOMP, React Frontend Web & Mobile-first View, DBML ERD)
- **Target Roles:** `ADMIN` (Schedule Management), `GUARD` (Shift Attendance & Incident Operations)

---

## 1. Executive Summary & Operational Context

The system extends the **Campus Security Surveillance System** by unifying **Guard Workforce Scheduling** with **AI Incident Dispatching and Tactical Field Communication**:

1. **Schedule Ownership (ADMIN-only):**
   - Work schedules, recurring weekly templates, shift generation, and roster modifications are managed strictly by users with the `ADMIN` role via the Admin Portal (`/admin/guard-schedules`).
   - Guards only have access to their personal shift schedule and attendance actions (Check-in / Check-out).

2. **Building & Campus Zone Operational Model:**
   - Security operations are scoped by `building` (e.g., `TOA_ALPHA`, `TOA_BETA` or unified `CO_SO_HCM` / `FPT_CAMPUS`).
   - **Mandatory Building Rule:** Every building or campus zone must have exactly **one Security Room (Phòng bảo vệ)** with **at least one guard on duty 24/7** (`INTERNAL_GUARD` or designated guard) monitoring live camera streams on a Desktop Web Dashboard and holding a walkie-talkie.
   - **Multiple Guards per Shift:** A single shift within a building supports multiple guards deployed concurrently across different checkpoints (e.g., Security Room, Main Gate, Floor Patrols).

3. **Hybrid Dispatching & Mobile Claim Workflow:**
   - When AI detects an incident via camera feed, the alert is broadcast in real-time to **all guards on duty in that building** via WebSockets.
   - The desktop screen in the Security Room sounds an audible siren and displays the camera feed.
   - Mobile devices of patrol guards ring/vibrate with an urgent incident card.
   - Guards coordinate via physical **walkie-talkies (bộ đàm)** on their assigned building channel.
   - The closest guard taps **"Claim (Tiếp nhận)"** on the Mobile Web app.
   - The system immediately synchronizes the incident status (`CLAIMED`), locking the claim button for other guards and updating the desktop display with the responder's name.
   - Upon on-site resolution, the responder submits a structured resolution report (`RESOLVED_VERIFIED` or `RESOLVED_DISMISSED`) with notes and optional snapshot evidence.

---

## 2. RBAC & Security Permission Matrix

| Endpoint / Feature | HTTP Method / Path | Allowed Roles | Description |
| :--- | :--- | :--- | :--- |
| **Manage Weekly Templates** | `GET, PUT /api/guard-schedules/templates` | `ADMIN` | View/Configure recurring weekly schedules |
| **Generate Concrete Shifts** | `POST /api/guard-schedules/generate` | `ADMIN` | Bulk generate daily shifts for a target date range |
| **Manage Daily Shifts** | `GET, POST, PUT, DELETE /api/guard-shifts/**` | `ADMIN` | Admin CRUD on concrete shift roster |
| **View Personal Shifts** | `GET /api/guard-shifts/my-shifts` | `GUARD` | Guard views personal assigned shifts |
| **Guard Check-in** | `POST /api/guard-shifts/{id}/check-in` | `GUARD` | Guard punches in for assigned shift |
| **Guard Check-out** | `POST /api/guard-shifts/{id}/check-out` | `GUARD` | Guard punches out of assigned shift |
| **View Active Incidents** | `GET /api/incidents/active` | `ADMIN`, `GUARD` | Filtered by user's assigned building/shift |
| **Claim Incident** | `POST /api/incidents/{id}/claim` | `GUARD` | Field guard claims primary response |
| **Resolve Incident** | `POST /api/incidents/{id}/resolve` | `GUARD` | Submits resolution categorization & notes |

---

## 3. Database Schema (Flyway Migration: V26)

### 3.1. Entity Relationship Diagram (DBML Syntax for ERD.txt)

```dbml
// ============================================================================
// GUARD SCHEDULES & INCIDENT MANAGEMENT (DATABASE DESIGN V5)
// ============================================================================

Table guard_schedule_templates {
  id UUID [pk, default: `gen_random_uuid()`]
  guard_id UUID [not null, note: 'FK to users']
  day_of_week INT [not null, note: '1: Sunday, 2: Monday, ..., 7: Saturday']
  shift_type VARCHAR(20) [not null, note: 'SHIFT_MORNING | SHIFT_AFTERNOON | SHIFT_NIGHT | SHIFT_OFF']
  start_time TIME [not null]
  end_time TIME [not null]
  area_id UUID [note: 'FK to areas — designated post (e.g., Security Room, Floor Patrol)']
  radio_channel VARCHAR(50) [note: 'Assigned radio channel, e.g., Kênh 2 - Alpha']
  notes TEXT [note: 'Duty notes']
  is_active BOOLEAN [not null, default: true]
  created_at TIMESTAMPTZ [not null, default: `CURRENT_TIMESTAMP`]
  updated_at TIMESTAMPTZ [not null, default: `CURRENT_TIMESTAMP`]
}

Table guard_shifts {
  id UUID [pk, default: `gen_random_uuid()`]
  guard_id UUID [not null, note: 'FK to users']
  shift_date DATE [not null, note: 'Concrete date YYYY-MM-DD']
  shift_type VARCHAR(20) [not null]
  start_time TIME [not null]
  end_time TIME [not null]
  area_id UUID [note: 'FK to areas']
  radio_channel VARCHAR(50)
  status VARCHAR(20) [not null, default: 'SCHEDULED', note: 'SCHEDULED | CHECKED_IN | COMPLETED | ABSENT | CANCELLED']
  check_in_at TIMESTAMPTZ
  check_out_at TIMESTAMPTZ
  notes TEXT
  created_at TIMESTAMPTZ [not null, default: `CURRENT_TIMESTAMP`]
  updated_at TIMESTAMPTZ [not null, default: `CURRENT_TIMESTAMP`]

  indexes {
    (guard_id, shift_date, start_time) [unique, name: 'uq_guard_shift_slot']
    (shift_date, area_id) [name: 'idx_guard_shifts_date_area']
  }
}

Table security_incidents {
  id UUID [pk, default: `gen_random_uuid()`]
  event_id VARCHAR(100) [note: 'Event ID from AI Service via Kafka']
  camera_code VARCHAR(50) [not null, note: 'Camera code triggering incident']
  area_id UUID [not null, note: 'FK to areas']
  building VARCHAR(50) [not null, note: 'Denormalized building/campus code for instant localized dispatching']
  event_type VARCHAR(50) [not null, note: 'UNAUTHORIZED_ENTRY | UNKNOWN_PERSON | AFTER_HOURS_ACCESS | LOITERING | CROWD_OVERCROWDING']
  image_url VARCHAR(512) [note: 'MinIO snapshot URL']
  detected_at TIMESTAMPTZ [not null, default: `CURRENT_TIMESTAMP`]
  status VARCHAR(30) [not null, default: 'NEW', note: 'NEW | CLAIMED | RESOLVED_VERIFIED | RESOLVED_DISMISSED']
  
  // Claim tracking
  claimed_by UUID [note: 'FK to users — responder who claimed']
  claimed_at TIMESTAMPTZ
  
  // Resolution details
  resolved_by UUID [note: 'FK to users — guard completing response']
  resolved_at TIMESTAMPTZ
  outcome VARCHAR(20) [note: 'VERIFIED | DISMISSED']
  resolution_category VARCHAR(50) [note: 'FALSE_ALARM | AUTHORIZED_EXCEPTION | REMINDED_DISPERSED | ESCORTED_OUT | REPORT_FILED | DETAINED_ESCALATED | OTHER']
  resolution_notes TEXT [note: 'Guard incident report summary']
  evidence_image_url VARCHAR(512) [note: 'On-site photo taken by guard after resolution']
  version INT [not null, default: 0, note: 'Optimistic locking counter']

  indexes {
    (building, status) [name: 'idx_incidents_building_status']
    (detected_at DESC) [name: 'idx_incidents_detected_at']
  }
}

// Relationships
Ref: guard_schedule_templates.guard_id > users.id
Ref: guard_schedule_templates.area_id > areas.id
Ref: guard_shifts.guard_id > users.id
Ref: guard_shifts.area_id > areas.id
Ref: security_incidents.area_id > areas.id
Ref: security_incidents.claimed_by > users.id
Ref: security_incidents.resolved_by > users.id
```

---

## 4. Incident Resolution Taxonomy

Aligned strictly with `Incident_Detection_Summary.md` (Review & Verify/Dismiss protocol):

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             INCIDENT OUTCOME                                │
├──────────────────────────────────────┬──────────────────────────────────────┤
│          DISMISSED                   │               VERIFIED               │
│    (Bác bỏ / Không vi phạm)          │         (Xác thực vi phạm thật)      │
├──────────────────────────────────────┼──────────────────────────────────────┤
│ 1. FALSE_ALARM                       │ 1. REMINDED_DISPERSED                │
│    Báo động giả (AI nhận nhầm bóng,  │    Nhắc nhở & giải tán (Áp dụng cho  │
│    vật thể hoặc tracking lỗi)        │    Loitering hoặc Crowd quá đông)    │
│                                      │                                      │
│ 2. AUTHORIZED_EXCEPTION              │ 2. ESCORTED_OUT                      │
│    Ngoại lệ hợp lệ (Người có thẩm    │    Mời/Áp giải ra khỏi khu vực cấm   │
│    quyền đi vào đột xuất / sự kiện)  │    (Unauthorized / After-hours)      │
│                                      │                                      │
│                                      │ 3. REPORT_FILED                      │
│                                      │    Lập biên bản vi phạm kỷ luật      │
│                                      │                                      │
│                                      │ 4. DETAINED_ESCALATED                │
│                                      │    Tạm giữ đối tượng / Bàn giao CA   │
│                                      │                                      │
│                                      │ 5. OTHER                             │
│                                      │    Xử lý khác (có ghi chú)           │
└──────────────────────────────────────┴──────────────────────────────────────┘
```

---

## 5. Real-Time WebSocket Architecture

### 5.1. Topic Endpoints & Payload Contracts

1. **Building Incident Broadcast Topic:**
   - **Destination:** `/topic/buildings/{buildingCode}/alerts`
   - **Trigger:** AI Service sends Kafka event -> Backend enriches with Area & Building -> Dispatches WebSocket message.
   - **Subscribers:**
     - Desktop Dashboard in the Building's Security Room.
     - Mobile Web App for all guards on duty in that building.
   - **Sample Payload:**
     ```json
     {
       "id": "7b0b925b-8610-4131-bb69-cf8004f141bf",
       "eventId": "EVT-20260915-001",
       "cameraCode": "CAM-001",
       "areaName": "Sảnh Tầng 1 Tòa Alpha",
       "building": "TOA_ALPHA",
       "eventType": "UNAUTHORIZED_ENTRY",
       "imageUrl": "http://localhost:9000/security-evidence/cam01_snap.jpg",
       "detectedAt": "2026-09-15T14:02:15+07:00",
       "status": "NEW",
       "radioChannel": "Kênh 2 - Tòa Alpha",
       "primarySecurityRoom": "Phòng Bảo vệ Tòa Alpha (G.01)"
     }
     ```

2. **Incident Lifecycle Synchronization Topic:**
   - **Destination:** `/topic/incidents/updates`
   - **Trigger:** Any guard calls `/api/incidents/{id}/claim` or `/api/incidents/{id}/resolve`.
   - **Subscribers:** All active Web & Mobile clients in the system.
   - **Sample Payload (Claimed):**
     ```json
     {
       "incidentId": "7b0b925b-8610-4131-bb69-cf8004f141bf",
       "status": "CLAIMED",
       "claimedBy": {
         "id": "e816a695-89f3-4f9e-a0e2-632b7a942ea7",
         "fullName": "Nguyễn Văn A",
         "radioChannel": "Kênh 2"
       },
       "claimedAt": "2026-09-15T14:02:40+07:00"
     }
     ```

### 5.2. Concurrency Control (Race-Condition Free Claiming)
When multiple guards attempt to claim the same incident simultaneously:
- The backend executes `UPDATE security_incidents SET status = 'CLAIMED', claimed_by = ?, claimed_at = ?, version = version + 1 WHERE id = ? AND status = 'NEW' AND version = ?`.
- First request succeeds (HTTP 200).
- Subsequent request updates 0 rows and throws `OptimisticLockException` -> Backend catches and returns `HTTP 409 Conflict` with:
  ```json
  {
    "code": "INCIDENT_ALREADY_CLAIMED",
    "message": "Sự việc đã được đồng đội [Nguyễn Văn A] tiếp nhận xử lý.",
    "claimedBy": "Nguyễn Văn A"
  }
  ```
- The losing mobile client disables its Claim button and displays the claimant's name gracefully.

---

## 6. Frontend UI Specifications

### 6.1. Admin Portal: Guard Schedule Management (`/admin/guard-schedules`)
- **Access:** Only users with `ROLE_ADMIN`.
- **Components:**
  - **Header Controls:** Week Picker (Default: Current week), Building Selector dropdown (`Tòa Alpha`, `Cơ sở Hồ Chí Minh`, etc.).
  - **Action Buttons:** `Sinh lịch tuần từ mẫu (Generate Shifts)`, `Tạo ca trực mới`, `Cấu hình Lịch mẫu tuần (Templates)`.
  - **Weekly Shift Matrix:**
    - Columns: Monday through Sunday with date headers.
    - Rows: Grouped list of guards.
    - Cells: Shift Cards with color coding (Morning: Blue, Afternoon: Orange, Night: Purple, Off: Gray).
    - Card details: Checkpoint name (`area.name`), Radio Channel (`radio_channel`), Attendance Badge (`Chưa đến`, `Đang trực`, `Hoàn thành`).
  - **Rule Validation Badge:** Highlights shift slots with an orange alert if a building does not have at least 1 guard assigned to the "Security Room" checkpoint.

### 6.2. Desktop Web: Security Room Command Center (`/guard/dashboard`)
- **Access:** `GUARD` (assigned to Security Room checkpoint in active shift) or `ADMIN`.
- **Layout:**
  - **Left Pane (65%):** Live Camera Grid (MediaMTX WebRTC streams) of the assigned building.
  - **Right Pane (35%):** Real-time Security Incident Feed & Radio Roster.
- **Interactions:**
  - Incoming incident triggers flashing red border + looping audio alert.
  - Snapshot viewer modal with zoom & pan.
  - Shows responder status in real-time as patrol guards claim and resolve alerts on mobile.
  - Audio alert automatically lowers on `CLAIMED` and silences completely on `RESOLVED`.

### 6.3. Mobile Web Responsive: Patrol Guard Field View (`/guard/mobile`)
- **Access:** `GUARD` on mobile browsers (viewport-optimized touch UI).
- **Sticky Top Bar:** Guard Profile, Assigned Building, Radio Channel badge, Connection indicator.
- **Attendance Card:** Current shift timing, Checkpoint name, Large touch button: `Nhận ca trực (Check-in)` / `Kết thúc ca (Check-out)`.
- **Incident Feed:**
  - Card with prominent snapshot image, detected time, incident badge, location.
  - Giant red button: **`TIẾP NHẬN XỬ LÝ (CLAIM)`**.
  - On Claimed: Card changes to yellow border with button: **`BÁO CÁO KẾT QUẢ & HOÀN TẤT`**.
- **Resolution Bottom Sheet / Modal:**
  - Outcome Toggle: `[ XÁC THỰC VI PHẠM (VERIFIED) ]` vs `[ BÁC BỎ (DISMISSED) ]`.
  - Category Pills: `Báo động giả`, `Ngoại lệ hợp lệ`, `Nhắc nhở giải tán`, `Áp giải ra ngoài`, `Lập biên bản`, `Tạm giữ bàn giao`.
  - Action Notes text area.
  - Optional Photo attachment (capture from mobile camera).
  - Submit Button: `Xác nhận giải quyết`.

---

## 7. Verification & Automated Testing Plan

1. **Database & Migrations:**
   - Flyway migration `V26__guard_schedules_and_incident_workflow.sql` executes without error against PostgreSQL 16.
   - Constraint checks validated: overnight shift times, unique guard slots.

2. **Backend Automated Tests:**
   - `GuardScheduleServiceTest`: Template loading, shift generation for full week, validation rule for building security room presence.
   - `IncidentWorkflowConcurrencyTest`: Simulates 2 concurrent threads claiming the same `NEW` incident; verifies exactly 1 succeeds and 1 receives `HTTP 409`.
   - `IncidentResolutionTest`: Verifies status transitions to `RESOLVED_VERIFIED` and `RESOLVED_DISMISSED` with complete audit trail.

3. **Frontend & WebSocket Verification:**
   - Verify STOMP subscriptions to `/topic/buildings/{buildingCode}/alerts` and `/topic/incidents/updates`.
   - Responsive design verification across mobile viewports (iPhone/Android screens: 375px - 430px) and desktop monitor (1920x1080).
