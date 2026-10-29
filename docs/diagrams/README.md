# Access control diagrams (Mermaid)

Diagrams for the access-control modules (access requests, guest visits, assigned personnel, areas, event schedules, entry decision), kept as Mermaid source so they can be reviewed and versioned with the code.

- Drawn from the code on `main` @ `84f5565` (migrations up to V89). **Updated 2026-10-25** against `main` @ `84c7760` for the access-rules changes (no review after start time, system finish of elapsed requests, one assigned-personnel record per grant, event mode opened by schedules only): SM-01, SM-03, SM-04, AD-01, AD-03, SQ-02 and CD-01 part 2. **Checked 2026-10-29** against `main` @ `e474968`: SM-01 … SM-05 unchanged; SM-06 (user account) added. Where code and documents disagree, the diagram follows the code; the differences are listed in [Differences from the documents](#differences-from-the-documents).
- Every `.mmd` file starts with `%% Title: … · Source: … · Generated from main @ <commit>`.
- SM-01 … SM-06 also have a PlantUML version (`state-*.puml`, same name) used in the SDD (Report 4 §4). Edit the `.mmd` and the `.puml` together.
- Paths in the tables are relative to `backend/src/main/java/com/fa26se040/icss/` unless they start with `frontend/`, `ai-service/` or `db/`.

## How to view

- **GitHub:** this README embeds every diagram in a ```` ```mermaid ```` block (section [Diagrams](#diagrams)); GitHub renders them. The `.mmd` files themselves are shown as plain text on GitHub.
- **VS Code:** open a `.mmd` file with a Mermaid preview extension, or preview this README.
- **SVG / PNG:** `npx -p @mermaid-js/mermaid-cli mmdc -i state-access-request.mmd -o state-access-request.svg` (run outside the repo; do not commit images).
- **Printing:** all diagrams fit A4. `seq-check-entry` (11 lifelines), `erd-access-control` and `activity-guest-visit` read best in landscape.

## Actors and names used in every diagram

| Name in diagrams | Code |
|---|---|
| Authorized User | role `NORMAL_USER` |
| Facility Manager | role `FACILITY_MANAGER` |
| Administrator | role `ADMIN` |
| Host | `NORMAL_USER` or `FACILITY_MANAGER` that creates a guest visit (`GuestRules.HOST_ROLES`) |
| AI Surveillance Engine | `ai-service` (calls `POST /api/access-control/check-entry`, extracts guest embeddings) |
| System (job) / System (backend and jobs) | `@Scheduled` jobs and backend checks |
| Access Requests Page | `frontend/src/pages/accessRequest/AccessRequestPage.jsx` (`/access-requests`) |
| Request Approval Page (tab Access requests / Guest visits) | `frontend/src/pages/approvals/ApprovalPage.jsx` (`/admin/access-requests?tab=requests\|guests`) |
| My Guest Visits Page | `frontend/src/pages/guestVisit/GuestVisitPage.jsx` (`/guest-visits`) |
| Guest Photos Page | `frontend/src/pages/guestVisit/GuestPhotoPage.jsx` (`/admin/guest-photos`) |
| Event Management Page | `frontend/src/pages/events/EventManagementPage.jsx` (`/admin/events`) |

## Diagram index

| Code | File | Type | Entity / workflow | Main code source (file: function) | Note |
|---|---|---|---|---|---|
| CTX-01 | `context-diagram.mmd` (+ `context-diagram.svg` for reports) | flowchart (ELK layout) | Whole system, level 0 | `controller/*` (`@PreAuthorize` roles), `security/GoogleTokenVerifier.java`, `service/NotificationService.java`, `service/CameraConnectionService.java`, `MediaMtxService.java`, `mobile/lib` | System = circle, external entities = rectangles, flows = nouns |
| SM-01 | `state-access-request.mmd` | stateDiagram-v2 | Access request | `enums/RequestStatus.java`, `service/AccessRequestService.java`, `service/AreaService.java: cancelRequestsBySystem` | System job finishes elapsed APPROVED requests (D5 resolved) |
| SM-02 | `state-guest-visit.mmd` | stateDiagram-v2 | Guest visit | `enums/GuestVisitStatus.java`, `service/GuestVisitService.java`, `service/GuestVisitJobService.java` | Biometric deletion is a note (it is `Guest.biometricStatus`) |
| SM-03 | `state-event-schedule.mmd` | stateDiagram-v2 | Event schedule | `enums/AreaEventScheduleStatus.java`, `service/AreaService.java` | |
| SM-04 | `state-assigned-personnel.mmd` | stateDiagram-v2 | Assigned personnel | `service/AreaAssignedPersonnelService.java: computeStatus` | Status computed, not stored. EXPIRED is final; no validity edit (D7 resolved) |
| SM-05 | `state-area.mmd` | stateDiagram-v2 | Area | `entity/Area.java`, `service/AreaService.java: create, update, deactivate, restore` | Business states, no status enum |
| SM-06 | `state-user.mmd` | stateDiagram-v2 | User account | `entity/User.java`, `service/UserService.java: createStaffAccount, toggleActive, softDelete, deleteBatch, restoreBatch` | Business states, no status enum. Only import batches can be restored |
| AD-01 | `activity-access-request.mmd` | flowchart, swimlanes | Request access workflow | `AccessRequestService`, `AccessDecisionService` | |
| AD-02 | `activity-guest-visit.mmd` | flowchart, swimlanes | Guest visit workflow | `GuestVisitService`, `GuestPhotoService.attach`, `GuestVisitJobService`, `GuestAccessDecisionService` | |
| AD-03 | `activity-event-schedule.mmd` | flowchart, swimlanes | Event schedule workflow | `AreaService`, `scheduler/AreaEventModeScheduler.java` | |
| SQ-01 | `seq-create-access-request.mmd` | sequenceDiagram | Create access request (individual) | `AccessRequestController.createIndividualRequest` → `AccessRequestService.createIndividualRequest` | Group path in a note |
| SQ-02 | `seq-review-access-request.mmd` | sequenceDiagram | Approve / reject request | `AccessRequestService.reviewRequest`, `AccessRequestRepository.reviewIfPending` | Non-PENDING gives 409, not 400 (D6) |
| SQ-03 | `seq-finish-access-request.mmd` | sequenceDiagram | Finish request | `AccessRequestService.finishRequest`, `AccessRequestRepository.finishIfApprovedStarted` | |
| SQ-04 | `seq-check-entry.mmd` | sequenceDiagram | Entry decision | `AccessControlController.checkCameraEntry` → `AccessDecisionService.checkCameraEntry, checkEntry` | Guest branch is wired (D1) |
| SQ-05 | `seq-create-event-schedule.mmd` | sequenceDiagram | Create event schedule | `AreaController.createEventSchedule` → `AreaService.createSchedule` | |
| SQ-06 | `seq-guest-visit-review.mmd` | sequenceDiagram | Review guest visit | `GuestVisitController.review` → `GuestVisitService.review` | |
| CD-01 | `class-access-control.mmd` + `class-access-control-services.mmd` | classDiagram | Part 1 domain model, part 2 controllers / services / jobs | `entity/*`, `enums/*`, `controller/*`, `service/*`, `scheduler/*` | Split: more than 25 classes (Q-07) |
| ER-01 | `erd-access-control.mmd` | erDiagram | Access-control tables | `db/migration` V19 … V89 | Actor FKs drawn as columns only (see ER-01 note) |

## Traceability of state machines

### SM-01 Access request

| State / transition | Source |
|---|---|
| States | `enums/RequestStatus.java` (DB CHECK `chk_access_requests_status`, V40) |
| [*] → PENDING (Authorized User) | `AccessRequestService.createIndividualRequest`, `createGroupRequest` (`status(RequestStatus.PENDING)`); `AccessRequestController` `@PreAuthorize("hasRole('NORMAL_USER')")` |
| PENDING → APPROVED / REJECTED (Facility Manager, before startTime) | `AccessRequestService.reviewRequest` (early check `hasStarted` → `ERR_AC_012`) → `AccessRequestRepository.reviewIfPending` (`… AND startTime > :reviewedAt`) |
| PENDING → CANCELLED, source USER (requester) | `AccessRequestService.cancelRequest` → `cancelIfPending`, `recordCancellation(…, CancelSource.USER, …)` |
| PENDING → EXPIRED (job, every 15 min) | `AccessRequestService.expireOverdueRequests` (`@Scheduled icss.scheduler.expire-overdue-cron`) → `findPendingOverdueRequestsForUpdate` (`startTime < now`), `expireOverdueRequestsByIds` |
| PENDING → CANCELLED, source SYSTEM | `AreaService.update` → `evaluateTypeChange` (new type PUBLIC, `PUBLIC_CANCEL_REASON`) → `cancelRequestsBySystem`; `AreaService.deactivate` → `AreaDependencyChecker.evaluate` → `cancelRequestsBySystem`; `AccessRequestService.cancelActiveRequestsOfRequester` ← `UserService.toggleActive`, `softDelete`, `deleteBatch`; all via `AccessRequestRepository.cancelBySystemIfStatus` |
| APPROVED → FINISHED (Facility Manager) | `AccessRequestService.finishRequest` → `finishIfApprovedStarted` (`startTime <= now`) |
| APPROVED → FINISHED (system job, every 15 min) | `AccessRequestService.finishElapsedApprovedRequests` (`@Scheduled icss.scheduler.finish-elapsed-cron`) → `AccessRequestRepository.findApprovedElapsedRequestsForUpdate` (`endTime <= now`), `finishElapsedRequestsByIds` (`finishedBy = NULL`, `FINISH_REASON_ELAPSED`) |
| APPROVED → CANCELLED, source STAFF (Facility Manager) | `AccessRequestService.cancelApprovedRequest` → `cancelIfApprovedNotStarted` (`startTime > now`), `recordCancellation(…, CancelSource.STAFF, …)` |
| APPROVED → CANCELLED, source SYSTEM | `AreaService.evaluateTypeChange` + `describeRuleViolation` → `cancelRequestsBySystem`; `AreaService.deactivate`; `AccessRequestService.cancelActiveRequestsOfRequester` (`findNotEndedByRequesterWithParticipants`, `endTime > now`) |
| Note: entry window | `AccessDecisionService.checkEntry` → `findCoveringRequestsForUser` (`startTime <= at < endTime`) |

### SM-02 Guest visit

| State / transition | Source |
|---|---|
| States | `enums/GuestVisitStatus.java` (DB CHECK `chk_guest_visits_status`, V60) |
| [*] → PENDING (Host) | `GuestVisitService.create` |
| PENDING → APPROVED / REJECTED (Facility Manager) | `GuestVisitService.review`, `recheckForApproval` |
| PENDING / APPROVED → CANCELLED (Host) | `GuestVisitService.cancel` (`deleteAllBiometrics` when the visit was APPROVED) |
| PENDING → CANCELLED (Administrator deactivates area) | `AreaService.deactivate` → `GuestVisitService.closeForAreaDeactivation` (PENDING branch) |
| APPROVED → REVOKED (Facility Manager) | `GuestVisitService.revoke` → `applyRevoke` |
| APPROVED → REVOKED (Administrator deactivates area) | `GuestVisitService.closeForAreaDeactivation` → `applyRevoke` |
| PENDING → EXPIRED (job) | `scheduler/GuestVisitScheduler.tick` → `GuestVisitJobService.runAll` → `expirePendingVisits` |
| APPROVED → COMPLETED (job) | `GuestVisitJobService.completeApprovedVisits` |
| Note: biometrics | `GuestPhotoService.attach` (PHOTO_READY), `GuestBiometricService.deleteBiometrics` (DELETED), `GuestVisitJobService.deleteDueBiometrics`, `anonymizeGuests`; `enums/GuestBiometricStatus.java` |

### SM-03 Event schedule

| State / transition | Source |
|---|---|
| States | `enums/AreaEventScheduleStatus.java` (DB CHECK `chk_area_event_schedules_status`, V56) |
| [*] → SCHEDULED (Facility Manager) | `AreaService.createSchedule` |
| SCHEDULED → SCHEDULED (edit) | `AreaService.updateSchedule` (requires SCHEDULED, ERR_AREA_040) |
| SCHEDULED → CANCELLED | `AreaService.cancelSchedule` |
| SCHEDULED → STARTED / FAILED (job) | `scheduler/AreaEventModeScheduler.tick` → `AreaService.processScheduledEventActivations` → `activateScheduleInTx` |
| Manual ENABLE / ADJUST removed | `AreaService.updateEventMode`: any action other than DISABLE → `ERR_AREA_058` |
| STARTED → COMPLETED | `AreaService.cleanupExpiredSessions` → `finishScheduleOfSession(…, COMPLETED)`; called by `processExpiredEventSessions` / `expireEventSessionForAreaInTx` and by `updateEventMode`, `createSchedule`, `updateSchedule`, `cancelSchedule`, `activateScheduleInTx` |
| STARTED → ENDED_EARLY (Facility Manager stops) | `AreaService.updateEventMode` (DISABLE branch) → `finishScheduleOfSession(…, ENDED_EARLY)` |

### SM-04 Assigned personnel

| State / transition | Source |
|---|---|
| States (computed) | `enums/AssignedPersonnelStatus.java`, `AreaAssignedPersonnelService.computeStatus`; stored columns `valid_from`, `valid_to`, `revoked_at` (V37) |
| [*] → UPCOMING / ACTIVE | `AreaAssignedPersonnelService.create` (`validFrom` defaults to now) |
| UPCOMING → ACTIVE, ACTIVE → EXPIRED (time) | `AreaAssignedPersonnelService.computeStatus` |
| No validity edit | `AreaAssignedPersonnelController` has no update endpoint; `AssignedPersonnelUpdateRequest` removed. `create` rejects `validFrom` older than `now − VALID_FROM_CLOCK_SKEW` (5 min) → `ERR_AP_013` |
| UPCOMING / ACTIVE → REVOKED (Facility Manager) | `AreaAssignedPersonnelService.revoke` (already revoked → `ERR_AP_009`; EXPIRED → `ERR_AP_012`) |
| UPCOMING / ACTIVE → REVOKED (account deactivated / deleted) | `UserService.toggleActive`, `softDelete`, `deleteBatch` → `AreaAssignedPersonnelService.revokeAllOfUser` (`findNotRevokedNotExpiredByUser`) |
| UPCOMING / ACTIVE → REVOKED (area deactivated) | `AreaService.deactivate` → `revokeAssignedPersonnelOnDeactivation` (`AreaDependencyChecker.evaluate` → `findNotRevokedNotExpired`) |

### SM-05 Area

| State / transition | Source |
|---|---|
| In operation / Deactivated | `entity/Area.java` (`isActive`, `deletedAt`); only `AreaService.deactivate` and `restore` write them |
| PUBLIC / CONFIDENTIAL / HIGHLY_CONFIDENTIAL | `enums/AreaLevel.java` (V83) |
| [*] → In operation | `AreaService.create` |
| Type change (6 transitions) | `AreaService.update` → `evaluateTypeChange` blockers: ERR_AREA_048 (active event, new type not Confidential), ERR_AREA_042 (SCHEDULED schedules), ERR_AREA_049 (to PUBLIC with active AP) |
| In operation → Deactivated | `AreaService.deactivate`, `AreaDependencyChecker.evaluate` (ERR_AREA_009, 051, 052, 042) |
| Deactivated → In operation | `AreaService.restore` (ERR_AREA_054, 055, 020) |
| Deactivated area cannot be edited | `AreaService.update` (`filter(a -> a.getDeletedAt() == null)` → ERR_AREA_002) |

### SM-06 User account

| State / transition | Source |
|---|---|
| ACTIVE / DEACTIVATED / DELETED | `entity/User.java` (`isActive`, `deletedAt`, `importBatchId`) |
| [*] → ACTIVE (create) | `UserService.createStaffAccount` (ADMIN role refused), mail via `sendAccountSetupEmailAfterCommit` |
| [*] → ACTIVE (import) | `UserBulkImportHelper` (face data via `FaceDataService.registerFace`, mail after the row commits) |
| ACTIVE ↔ DEACTIVATED | `UserService.toggleActive` (not own account); deactivation calls `cancelActiveRequestsOfRequester`, `revokeAllOfUser`, `closeWebSocketSessionsAfterCommit` |
| → DELETED (one account) | `UserService.softDelete` (not own account, same side effects) |
| → DELETED (batch) | `UserService.deleteBatch` → `UserRepository.softDeleteByImportBatchId` |
| DELETED → ACTIVE | `UserService.restoreBatch` (skips accounts whose code or e-mail is used by a live account) |
| Login refused | `AuthService` (`findByEmailAndIsActiveTrue`), `security/JwtAuthenticationFilter.java` |
| Hosted guest visits | Status unchanged; `GuestAccessDecisionService` denies entry with `HOST_LOST_ACCESS` |

## ER-01 note

Columns that only record who acted (`reviewer_id` is drawn, but `cancelled_by`, `finished_by`, `created_by`, `revoked_by`, `started_by`, `ended_by`, `reviewed_by`, `consent_confirmed_by`, `changed_by`) are FKs to `users.id` listed as `FK` columns without a relationship line, so the diagram stays readable on A4. `floors.building_id` → `buildings` is outside the module and not drawn. `area_event_sessions.schedule_id` (V56) and `area_event_schedules.session_id` (V55_1) are both real FKs.

## Differences from the documents

The diagrams follow the code. Differences found with `r3-draft` (II-5-1 business rules, II-3-1-2 screens, II-2-1 actors):

| # | Document says | Code does |
|---|---|---|
| D1 | II-5-1 §6 intro: guest entry decision "not yet called by any entry flow" | `AccessDecisionService.checkCameraEntry` calls `GuestAccessDecisionService.checkGuestEntry` for guest codes, and `ai-service/src/core/face_matcher.py` loads `guest_face_embeddings` of APPROVED, PHOTO_READY guests. BR-CE-03 in the same draft already says so |
| D2 | BR-GV-04: selectable areas are "Internal Confidential and Contact-Required Confidential" | Since V83 only `CONFIDENTIAL` (`GuestRules.GUEST_AREA_LEVELS`) |
| D3 | BR-GV-27: immediate deletion for CANCELLED / REVOKED / EXPIRED | Also REJECTED (`GuestVisitJobService.CLOSED_WITHOUT_VISIT`) |
| D4 | BR-GV-38 describes a "photo without a valid embedding" status | `PHOTO_ONLY` exists in `GuestBiometricStatus` but no code sets it; `GuestPhotoService.attach` rejects a photo without a valid embedding |
| D5 | (not stated) | Resolved on `84c7760`: the job `finishElapsedApprovedRequests` moves elapsed APPROVED requests to FINISHED |
| D6 | (task brief) review returns 400 for a wrong status | Non-PENDING gives 409 (`ConcurrentReviewException`); 400 only for bad input or failed re-check (matches BR-RQ-40) |
| D7 | BR-AP-08 forbids changes to revoked records only | Resolved on `84c7760`: validity edit removed (one record per grant), and `revoke` refuses EXPIRED records |
| D8 | II-2-1 actors: "AI Service", "System Scheduler", "Guard" | Diagrams use the task names: AI Surveillance Engine, System (job), Security Officer (code `GUARD`) |

## Context diagram notes

| Choice | Reason |
|---|---|
| AI Surveillance Engine, mobile app, MediaMTX, MinIO, Kafka, PostgreSQL are **inside** the system circle | They are built or deployed by the team as part of ICSSS |
| Guest is **not** an external entity | A guest never uses the system; the host registers the visit and the Administrator attaches the photo (decision D1, 06/10) |
| Google Identity Service | The web client gets a Google ID token in the browser; the backend verifies it with `GoogleIdTokenVerifier`, which downloads Google signing certificates |
| Google Drive (dashed) | **Planned, not yet in code** (decision 25/10: archive every 1-minute recording segment to the team's Google Drive; owner Bảo). Shown dashed so the diagram does not claim an implemented integration. Turn it solid when the uploader is merged |
| No push service | The guard app gets alerts over STOMP while running; there is no FCM (`mobile/lib/core/notifications/local_notification_service.dart`) |
| Two files, same content | `context-diagram.mmd` is the Mermaid source (Review 2 requirement). `context-diagram.svg` is a hand-laid version of the **same flows** for reports and slides (users on the left, external systems on the right, one inbound and one outbound arrow per entity). Change both together |

## Diagrams

Embedded copies of the `.mmd` files (the `.mmd` file is the source; keep both in sync).

### CTX-01 Context diagram (level 0)

![CTX-01 context diagram](context-diagram.svg)

Mermaid source:

```mermaid
---
config:
  layout: elk
---
%% Title: CTX-01 Context diagram of ICSSS (level 0) · Source: controller/*.java (@PreAuthorize roles), security/GoogleTokenVerifier.java, service/NotificationService.java (JavaMailSender, Gmail SMTP), service/CameraConnectionService.java and MediaMtxService.java (RTSP), mobile/lib (Security Officer app) · Generated from main @ 84c7760
%% Notation: the system is one circle; every external entity is a separate rectangle; every flow is labelled with a noun (data, not an action).
flowchart LR
    AU["Authorized User"]
    FM["Facility Manager"]
    AD["Administrator"]
    SO["Security Officer"]

    SYS(("ICSSS<br/>Intelligent Campus<br/>Security Surveillance<br/>System"))

    CAM["IP Camera"]
    GID["Google Identity Service"]
    SMTP["E-mail Service<br/>(Gmail SMTP)"]
    GDR["Google Drive<br/>(planned, not yet in code)"]

    AU -->|"Login credentials<br/>Access requests<br/>Guest visit registrations"| SYS
    FM -->|"Review decisions<br/>Event schedules<br/>Personnel assignments<br/>User access levels<br/>Guard shift plans"| SYS
    AD -->|"User accounts<br/>Area definitions<br/>Camera configurations<br/>System parameters<br/>Guest photos with consent"| SYS
    SO -->|"Incident claims<br/>Verification results<br/>Shift change requests"| SYS

    SYS -->|"Request status<br/>Notifications<br/>Personal access history"| AU
    SYS -->|"Pending requests<br/>Escalated incident alerts<br/>Access history<br/>Audit records<br/>Dashboard statistics"| FM
    SYS -->|"Import results<br/>Audit records<br/>Camera status<br/>Configuration history"| AD
    SYS -->|"Real-time incident alerts<br/>Live video<br/>Shift schedule"| SO

    SYS -->|"Stream connection request"| CAM
    CAM -->|"Video stream"| SYS
    SYS -->|"Signing key request"| GID
    GID -->|"Google signing certificates"| SYS
    SYS -->|"Account setup e-mails<br/>Password reset e-mails"| SMTP
    SMTP -->|"Send result"| SYS

    %% Planned: continuous recording archive (1-minute segments). Dashed = not implemented yet.
    SYS -.->|"Recorded video segments"| GDR
    GDR -.->|"Archived video files"| SYS

    classDef ext fill:#ffffff,stroke:#1f2937,stroke-width:1.5px,color:#111827
    classDef sys fill:#e0ecff,stroke:#1d4ed8,stroke-width:2px,color:#0f172a
    class AU,FM,AD,SO,CAM,GID,SMTP ext
    classDef planned fill:#fff4e6,stroke:#c2410c,stroke-width:1.5px,stroke-dasharray:6 4,color:#4a2a10
    class GDR planned
    class SYS sys
```

### SM-01 Access request lifecycle

Source file: [`state-access-request.mmd`](state-access-request.mmd)

```mermaid
%% Title: SM-01 Access request lifecycle · Source: enums/RequestStatus.java, service/AccessRequestService.java, service/AreaService.java (cancelRequestsBySystem) · Generated from main @ 84c7760
stateDiagram-v2
    direction LR

    state "PENDING · awaiting review" as PENDING
    state "APPROVED · grants entry inside window" as APPROVED
    state "REJECTED" as REJECTED
    state "CANCELLED" as CANCELLED
    state "EXPIRED" as EXPIRED
    state "FINISHED" as FINISHED

    [*] --> PENDING : Authorized User creates request [area not Public, valid window, no overlap]

    PENDING --> APPROVED : Facility Manager approves [now before startTime, reviewer is not requester, members re-checked, UPDATE WHERE status = PENDING AND startTime > now hits 1 row]
    PENDING --> REJECTED : Facility Manager rejects [now before startTime, reason required, UPDATE WHERE status = PENDING AND startTime > now hits 1 row]
    PENDING --> CANCELLED : Authorized User (requester) cancels [UPDATE WHERE status = PENDING hits 1 row] / source USER
    PENDING --> EXPIRED : System job expireOverdueRequests every 15 min [startTime before now; review after start is refused with ERR_AC_012]
    PENDING --> CANCELLED : System [area changed to Public, area deactivated, or requester deactivated or deleted] / source SYSTEM

    APPROVED --> FINISHED : Facility Manager finishes [now at or after startTime, reason required]
    APPROVED --> FINISHED : System job finishElapsedApprovedRequests every 15 min [endTime at or before now] / finished_by NULL, reason "Hết thời gian hiệu lực", no notification
    APPROVED --> CANCELLED : Facility Manager cancels approved request [now before startTime, reason required] / source STAFF
    APPROVED --> CANCELLED : System [area type change breaks request rule, area deactivated, or requester deactivated or deleted, with endTime after now] / source SYSTEM

    REJECTED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
    FINISHED --> [*]

    note right of APPROVED
        Entry is granted only while
        startTime <= at < endTime (checkEntry).
        After endTime the system job moves it to FINISHED.
    end note
```

### SM-02 Guest visit lifecycle

Source file: [`state-guest-visit.mmd`](state-guest-visit.mmd)

```mermaid
%% Title: SM-02 Guest visit lifecycle · Source: enums/GuestVisitStatus.java, service/GuestVisitService.java, service/GuestVisitJobService.java, scheduler/GuestVisitScheduler.java · Generated from main @ 84f5565
stateDiagram-v2
    direction LR

    state "PENDING · awaiting review" as PENDING
    state "APPROVED · guests may enter inside window" as APPROVED
    state "REJECTED" as REJECTED
    state "CANCELLED" as CANCELLED
    state "REVOKED" as REVOKED
    state "EXPIRED" as EXPIRED
    state "COMPLETED" as COMPLETED

    [*] --> PENDING : Host (Authorized User or Facility Manager) creates visit [host eligible, Confidential areas, host covers areas for whole window]

    PENDING --> APPROVED : Facility Manager approves [version matches, reviewer is not host, now before startTime, rules re-checked]
    PENDING --> REJECTED : Facility Manager rejects [version matches, reviewer is not host, now before startTime, reason required]
    PENDING --> CANCELLED : Host cancels [version matches, now before endTime]
    PENDING --> CANCELLED : Administrator deactivates an area of the visit [now before endTime]
    PENDING --> EXPIRED : System job expirePendingVisits every minute [now at or after startTime]

    APPROVED --> CANCELLED : Host cancels [version matches, now before endTime] / biometrics deleted
    APPROVED --> REVOKED : Facility Manager revokes [version matches, reviewer is not host, reason required, now before endTime] / biometrics deleted
    APPROVED --> REVOKED : Administrator deactivates an area of the visit [now before endTime] / biometrics deleted
    APPROVED --> COMPLETED : System job completeApprovedVisits every minute [now at or after endTime]

    REJECTED --> [*]
    CANCELLED --> [*]
    REVOKED --> [*]
    EXPIRED --> [*]
    COMPLETED --> [*]

    note right of APPROVED
        Biometric deletion is not a visit state.
        It is Guest.biometricStatus (GuestBiometricStatus):
        NO_PHOTO, PHOTO_READY after Administrator attaches photo with consent,
        DELETED on cancel or revoke of an APPROVED visit, on expiry,
        or by job deleteDueBiometrics at endTime + GUEST_FACE_RETENTION_HOURS.
        PHOTO_ONLY exists in the enum but no code sets it.
    end note
```

### SM-03 Event schedule lifecycle

Source file: [`state-event-schedule.mmd`](state-event-schedule.mmd)

```mermaid
%% Title: SM-03 Event schedule lifecycle · Source: enums/AreaEventScheduleStatus.java, service/AreaService.java (createSchedule, updateSchedule, cancelSchedule, activateScheduleInTx, cleanupExpiredSessions, updateEventMode, finishScheduleOfSession), scheduler/AreaEventModeScheduler.java · Generated from main @ 84c7760
stateDiagram-v2
    direction LR

    state "SCHEDULED · booked, not started" as SCHEDULED
    state "STARTED · event session open" as STARTED
    state "CANCELLED" as CANCELLED
    state "FAILED" as FAILED
    state "COMPLETED" as COMPLETED
    state "ENDED_EARLY" as ENDED_EARLY

    [*] --> SCHEDULED : Facility Manager creates schedule [active Confidential area, startAt after now, no overlap, budget ok]

    SCHEDULED --> SCHEDULED : Facility Manager edits schedule [same checks as create, note required]
    SCHEDULED --> CANCELLED : Facility Manager cancels schedule [reason code and note required]
    SCHEDULED --> STARTED : System job AreaEventModeScheduler every minute [startAt reached, endAt after now, area active and Confidential] / session opened
    SCHEDULED --> FAILED : System job AreaEventModeScheduler every minute [startAt reached and endAt passed, or area inactive, or area no longer Confidential]

    STARTED --> COMPLETED : System closes expired session [plannedEnd reached, by job every minute or by any schedule or event-mode write on the area]
    STARTED --> ENDED_EARLY : Facility Manager stops event (event mode DISABLE)

    CANCELLED --> [*]
    FAILED --> [*]
    COMPLETED --> [*]
    ENDED_EARLY --> [*]

    note right of STARTED
        Manual enable / adjust removed:
        PATCH /event-mode accepts DISABLE only
        (ENABLE, ADJUST -> 400 ERR_AREA_058).
        An event opens only from a schedule.
    end note
```

### SM-04 Assigned personnel (AP) status

Source file: [`state-assigned-personnel.mmd`](state-assigned-personnel.mmd)

```mermaid
%% Title: SM-04 Assigned personnel (AP) status · Source: enums/AssignedPersonnelStatus.java, service/AreaAssignedPersonnelService.java (create, revoke, revokeAllOfUser, computeStatus), service/AreaService.java (revokeAssignedPersonnelOnDeactivation), service/UserService.java · Generated from main @ 84c7760
stateDiagram-v2
    direction LR

    state "UPCOMING · computed" as UPCOMING
    state "ACTIVE · computed" as ACTIVE
    state "EXPIRED · computed" as EXPIRED
    state "REVOKED · stored as revoked_at" as REVOKED

    [*] --> UPCOMING : Facility Manager assigns user [validFrom after now, no overlap]
    [*] --> ACTIVE : Facility Manager assigns user [validFrom default now, not more than 5 min in the past (ERR_AP_013)]

    UPCOMING --> ACTIVE : Time passes [now at or after validFrom]
    ACTIVE --> EXPIRED : Time passes [validTo set and now at or after validTo]

    UPCOMING --> REVOKED : Facility Manager revokes [reason required]
    ACTIVE --> REVOKED : Facility Manager revokes [reason required]

    UPCOMING --> REVOKED : Administrator deactivates or deletes the user account (BR-AP-24, BR-AP-25)
    ACTIVE --> REVOKED : Administrator deactivates or deletes the user account (BR-AP-24, BR-AP-25)
    UPCOMING --> REVOKED : Administrator deactivates the area (BR-AD-04)
    ACTIVE --> REVOKED : Administrator deactivates the area (BR-AD-04)

    REVOKED --> [*]
    EXPIRED --> [*]

    note right of EXPIRED
        One record per grant: validity cannot be edited.
        To grant again, the Facility Manager creates a new record
        (it may start exactly at the previous validTo).
        Revoking an EXPIRED record is refused (409 ERR_AP_012).
    end note

    note left of REVOKED
        Status is NOT stored. computeStatus(ap, now):
        revoked_at set -> REVOKED
        now before valid_from -> UPCOMING
        valid_to set and now at or after valid_to -> EXPIRED
        otherwise ACTIVE
    end note
```

### SM-05 Area lifecycle (active / deactivated, area type)

Source file: [`state-area.mmd`](state-area.mmd)

```mermaid
%% Title: SM-05 Area lifecycle (active / deactivated, area type) · Source: entity/Area.java (isActive, deletedAt, areaLevel), enums/AreaLevel.java, service/AreaService.java (create, update, deactivate, restore), service/AreaDependencyChecker.java · Generated from main @ 84f5565
stateDiagram-v2
    direction LR

    [*] --> InOperation : Administrator creates area

    state "In operation · is_active = true, deleted_at empty" as InOperation {
        state "PUBLIC · level 1" as PUBLIC
        state "CONFIDENTIAL · level 2" as CONFIDENTIAL
        state "HIGHLY_CONFIDENTIAL · level 3" as HIGHLY_CONFIDENTIAL

        [*] --> PUBLIC : type chosen at creation
        [*] --> CONFIDENTIAL : type chosen at creation
        [*] --> HIGHLY_CONFIDENTIAL : type chosen at creation

        CONFIDENTIAL --> PUBLIC : Administrator changes type [reason, no active event, no SCHEDULED schedule, no active AP]
        CONFIDENTIAL --> HIGHLY_CONFIDENTIAL : Administrator changes type [reason, no active event, no SCHEDULED schedule]
        HIGHLY_CONFIDENTIAL --> PUBLIC : Administrator changes type [reason, no active AP]
        HIGHLY_CONFIDENTIAL --> CONFIDENTIAL : Administrator changes type [reason]
        PUBLIC --> CONFIDENTIAL : Administrator changes type [reason]
        PUBLIC --> HIGHLY_CONFIDENTIAL : Administrator changes type [reason]
    }

    state "Deactivated · is_active = false, deleted_at set" as Deactivated

    InOperation --> Deactivated : Administrator deactivates [reason, version, no camera, no open incident, no active event, no SCHEDULED schedule] / AP revoked, requests and guest visits closed
    Deactivated --> InOperation : Administrator restores [reason, version, floor and building active, name unique on floor] / nothing restored automatically

    note right of Deactivated
        Type change: system cancels APPROVED requests that break the new type rules
        (and all PENDING + APPROVED when new type is PUBLIC) in the same transaction.
        A deactivated area cannot be edited or have its type changed (ERR_AREA_002).
    end note
```

### SM-06 User account lifecycle

Source file: [`state-user.mmd`](state-user.mmd)

```mermaid
%% Title: SM-06 User account lifecycle · Source: entity/User.java (isActive, deletedAt, importBatchId), service/UserService.java (createStaffAccount, bulkImport*, toggleActive, softDelete, deleteBatch, restoreBatch), service/UserBulkImportHelper.java, repository/UserRepository.java (softDeleteByImportBatchId), security/JwtAuthenticationFilter.java, service/AuthService.java · Generated from main @ e474968
stateDiagram-v2
    direction LR

    state "ACTIVE · is_active = true, deleted_at empty" as ACTIVE
    state "DEACTIVATED · is_active = false, deleted_at empty" as DEACTIVATED
    state "DELETED · is_active = false, deleted_at set (soft delete)" as DELETED

    [*] --> ACTIVE : Administrator creates staff account [role FACILITY_MANAGER, GUARD or NORMAL_USER; never ADMIN] / credentials e-mailed after commit
    [*] --> ACTIVE : Administrator imports accounts from file [valid row with face photo] / import_batch_id set, face data registered, credentials e-mailed after the row commits

    ACTIVE --> DEACTIVATED : Administrator deactivates [not own account] / requests PENDING or APPROVED cancelled (source SYSTEM), assigned personnel revoked, WebSocket sessions closed, AI face cache refreshed
    DEACTIVATED --> ACTIVE : Administrator reactivates / nothing restored automatically, AI face cache refreshed

    ACTIVE --> DELETED : Administrator deletes account [not own account] / same side effects as deactivation
    DEACTIVATED --> DELETED : Administrator deletes account [not own account]
    ACTIVE --> DELETED : Administrator removes import batch / same side effects for every account of the batch
    DEACTIVATED --> DELETED : Administrator removes import batch

    DELETED --> ACTIVE : Administrator restores import batch [account belongs to the batch, user code and e-mail not used by another live account] / skipped accounts reported

    note right of DEACTIVATED
        DEACTIVATED and DELETED accounts cannot log in
        (password, Google or reset link: findByEmailAndIsActiveTrue)
        and existing JWTs are refused by JwtAuthenticationFilter.
        Guest visits hosted by the account keep their status;
        guest entry is denied at decision time (HOST_LOST_ACCESS).
    end note

    note left of DELETED
        There is no single-account restore.
        Only accounts created by import (import_batch_id set)
        can return, and they always return as ACTIVE.
    end note
```

### AD-01 Access request workflow

Source file: [`activity-access-request.mmd`](activity-access-request.mmd)

```mermaid
%% Title: AD-01 Access request workflow · Source: service/AccessRequestService.java (createIndividualRequest, createGroupRequest, reviewRequest, expireOverdueRequests, finishRequest, cancelApprovedRequest, finishElapsedApprovedRequests), service/AccessDecisionService.java (checkCameraEntry, checkEntry), frontend pages/accessRequest/AccessRequestPage.jsx, pages/approvals/ApprovalPage.jsx · Generated from main @ 84c7760
flowchart TB
    subgraph AU["Authorized User"]
        start((Start))
        u1["Open Access Requests Page, fill form: type, area, window, purpose, members"]
        u2["See error message and correct the form"]
    end

    subgraph FM["Facility Manager"]
        f1["Open Request Approval Page, tab Access requests"]
        f2{"Approve?"}
        f3["Enter rejection reason"]
        f4["See 409 warning, list refreshed"]
        f5{"End the approved request early?"}
        f6["Finish after start or cancel before start, with reason"]
    end

    subgraph SYS["System (backend and jobs)"]
        s1{"Rules satisfied? area not Public, window, level, members, no overlap"}
        s2["Save request as PENDING, notify Facility Managers"]
        s3{"Reviewed before startTime?"}
        s4["Review after startTime refused (ERR_AC_012); job expireOverdueRequests sets EXPIRED, notify requester"]
        s5{"Conditional UPDATE WHERE status = PENDING hits 1 row?"}
        s6["Request REJECTED, notify requester and members"]
        s7["Request APPROVED, notify requester and members"]
        s8{"checkEntry: APPROVED request covers detection time? (after AP, level, event, incident)"}
        s9["Write camera access log: AUTHORIZED_ACCESS"]
        s9b["Write camera access log: denied result and reason"]
        s10["Conditional UPDATE to FINISHED or CANCELLED, notify participants"]
        s11["Job finishElapsedApprovedRequests: endTime passed, set FINISHED, finished_by empty, no notification"]
    end

    subgraph AI["AI Surveillance Engine"]
        i1["Recognise participant on a camera of the area, call check-entry"]
        i2["Mark person authorized"]
        i3["Mark person unauthorized, raise alert"]
    end

    stop(((End)))

    start --> u1 --> s1
    s1 -->|no| u2 --> u1
    s1 -->|yes| s2 --> s3
    s3 -->|no| s4 --> stop
    s3 -->|yes| f1 --> f2
    f2 -->|reject| f3 --> s5
    f2 -->|approve| s5
    s5 -->|no, handled by someone else| f4 --> stop
    s5 -->|yes, reject| s6 --> stop
    s5 -->|yes, approve| s7
    s7 --> i1 --> s8
    s8 -->|allowed, source ACCESS_REQUEST| s9 --> i2 --> f5
    s8 -->|denied, source NONE| s9b --> i3 --> f5
    f5 -->|yes| f6 --> s10 --> stop
    f5 -->|no, window ends| s11 --> stop
```

### AD-02 Guest visit workflow

Source file: [`activity-guest-visit.mmd`](activity-guest-visit.mmd)

```mermaid
%% Title: AD-02 Guest visit workflow · Source: service/GuestVisitService.java (create, review, cancel, revoke), service/GuestPhotoService.java (attach), service/GuestVisitJobService.java (expirePendingVisits, completeApprovedVisits, deleteDueBiometrics, anonymizeGuests), service/AccessDecisionService.java (checkCameraEntry), service/GuestAccessDecisionService.java (checkGuestEntry), ai-service src/core/face_matcher.py · Generated from main @ 84f5565
flowchart TB
    subgraph HOST["Host (Authorized User or Facility Manager)"]
        start((Start))
        h1["Open My Guest Visits Page, create visit: guests, purpose, Confidential areas, window"]
        h2["Cancel visit"]
    end

    subgraph FM["Facility Manager"]
        f1["Open Request Approval Page, tab Guest visits"]
        f2{"Approve?"}
        f3["Revoke visit with reason"]
    end

    subgraph ADM["Administrator"]
        a1["Guest confirms consent at desk, Administrator attaches photo on Guest Photos Page"]
    end

    subgraph AI["AI Surveillance Engine"]
        i1["Extract 512-d face embedding from photo"]
        i2["Recognise guest on a camera, call check-entry with guest id"]
    end

    subgraph SYS["System (backend and jobs)"]
        s1{"Host eligible, areas Confidential, host covers areas for whole window?"}
        s2["Save visit PENDING, notify Facility Managers"]
        s3{"Reviewed before startTime?"}
        s4["Job sets EXPIRED, notify host"]
        s5["Visit REJECTED, notify host"]
        s6["Re-check rules, visit APPROVED, notify host and Administrators"]
        s7{"Exactly one face and valid embedding?"}
        s8["Store photo and embedding, guest PHOTO_READY"]
        s9{"checkGuestEntry: APPROVED, inside window, area in visit, host still covers, PHOTO_READY?"}
        s10["Write camera access log: GUEST_VISIT allowed or denied with reason"]
        s11{"How does the visit end?"}
        s12["Job sets COMPLETED at endTime"]
        s13["Delete photo and embedding: at once for cancel or revoke, at endTime + retention hours for completed"]
        s14["Job anonymises guest after record retention days"]
    end

    stop(((End)))

    start --> h1 --> s1
    s1 -->|no, error shown| h1
    s1 -->|yes| s2 --> s3
    s3 -->|no| s4 --> stop
    s3 -->|yes| f1 --> f2
    f2 -->|reject with reason| s5 --> stop
    f2 -->|approve| s6 --> a1 --> i1 --> s7
    s7 -->|no| a1
    s7 -->|yes| s8 --> i2 --> s9
    s9 -->|allowed, source GUEST_VISIT| s10
    s9 -->|denied, reason code| s10
    s10 --> s11
    s11 -->|host cancels| h2 --> s13
    s11 -->|Facility Manager revokes| f3 --> s13
    s11 -->|endTime reached| s12 --> s13
    s13 --> s14 --> stop
```

### AD-03 Event schedule workflow

Source file: [`activity-event-schedule.mmd`](activity-event-schedule.mmd)

```mermaid
%% Title: AD-03 Event schedule workflow · Source: service/AreaService.java (createSchedule, updateSchedule, cancelSchedule, processScheduleReminders, activateScheduleInTx, updateEventMode, cleanupExpiredSessions), scheduler/AreaEventModeScheduler.java, service/AccessDecisionService.java (checkEntry), frontend pages/events/EventManagementPage.jsx · Generated from main @ 84c7760
flowchart TB
    subgraph FM["Facility Manager"]
        start((Start))
        f1["Open Event Management Page, Create Event: title, Confidential area, start and end, reason"]
        f2{"Change plan before start?"}
        f3["Cancel event with reason code and note"]
        f4{"Stop the running event early?"}
        f5["Stop event, event mode DISABLE"]
    end

    subgraph SYS["System (backend and jobs)"]
        s1{"Area active and Confidential, future start, duration, no overlap, budget ok?"}
        s2["Save schedule SCHEDULED, audit, notify guards"]
        s3["Schedule CANCELLED, notify guards"]
        s4["Job reminds creator before startAt"]
        s5{"Job at startAt: endAt after now, area active and Confidential?"}
        s6["Schedule FAILED, notify creator"]
        s7["Open event session, area open to members, schedule STARTED, notify guards"]
        s8["checkEntry allows source OPEN_EVENT when AP and level do not (event opens only from a schedule; no manual enable)"]
        s9["Close session, schedule ENDED_EARLY, notify guards"]
        s10["Job closes session at planned end, schedule COMPLETED, area closed"]
    end

    subgraph AI["AI Surveillance Engine"]
        i1["Recognise member on a camera of the area, call check-entry"]
    end

    stop(((End)))

    start --> f1 --> s1
    s1 -->|no, error shown| f1
    s1 -->|yes| s2 --> f2
    f2 -->|edit| s1
    f2 -->|cancel| f3 --> s3 --> stop
    f2 -->|no| s4 --> s5
    s5 -->|no| s6 --> stop
    s5 -->|yes| s7 --> i1 --> s8 --> f4
    f4 -->|yes| f5 --> s9 --> stop
    f4 -->|no| s10 --> stop
```

### SQ-01 Create access request (individual)

Source file: [`seq-create-access-request.mmd`](seq-create-access-request.mmd)

```mermaid
%% Title: SQ-01 Create access request (individual) · Source: frontend pages/accessRequest/AccessRequestPage.jsx, services/accessRequestService.js (createIndividualRequest), controller/AccessRequestController.java (createIndividualRequest), service/AccessRequestService.java (createIndividualRequest, validateCommonRules, requireAreaAvailableForRequest, validateNoOverlap) · Generated from main @ 84f5565
sequenceDiagram
    autonumber
    actor AU as Authorized User
    participant P as Access Requests Page
    participant C as :AccessRequestController
    participant S as :AccessRequestService
    participant UR as :UserRepository
    participant AR as :AreaRepository
    participant RR as :AccessRequestRepository
    participant AS as :AuditService
    participant NS as :InAppNotificationService

    AU->>P: Fill form (Individual, area, start, end, purpose) and submit
    activate P
    P->>C: POST /api/access-requests/individual
    activate C
    C->>S: createIndividualRequest(request, actorEmail)
    activate S
    S->>UR: findByEmail(actorEmail)
    S->>AR: findByIdAndDeletedAtIsNull(areaId)
    S->>S: lockParticipants(requester), validateCommonRules(area, startTime, endTime)
    S->>S: requireAreaAvailableForRequest(area, requester, INDIVIDUAL)
    alt Failure: rule violated (Public area, window, advance days, duration, level gap)
        S-->>C: IllegalArgumentException
        C-->>P: 400 Bad Request with message
    else Rules satisfied
        S->>RR: findOverlappingRequests(userIds, areaId, startTime, endTime, [PENDING, APPROVED])
        alt Failure: overlapping request exists
            S-->>C: DuplicateResourceException
            C-->>P: 409 Conflict (code and name of each overlapping participant)
        else Success
            S->>RR: save(AccessRequest status PENDING)
            S->>AS: record(ACCESS_REQUEST, CREATE, snapshot)
            S->>NS: createForUsers(active Facility Managers, NEW_REQUEST_PENDING)
            S-->>C: AccessRequestResponse
            C-->>P: 201 Created
        end
    end
    deactivate S
    deactivate C
    P-->>AU: Show result, refresh My requests
    deactivate P

    note over P,S: Group request: POST /api/access-requests/group, createGroupRequest also resolves member codes, marks sponsored members and notifies members (ADDED_TO_GROUP)
```

### SQ-02 Review (approve / reject) access request

Source file: [`seq-review-access-request.mmd`](seq-review-access-request.mmd)

```mermaid
%% Title: SQ-02 Review (approve / reject) access request · Source: frontend pages/approvals/ApprovalPage.jsx, pages/accessRequest/AccessRequestReviewPage.jsx (handleConfirmApprove), controller/AccessRequestController.java (reviewRequest), service/AccessRequestService.java (reviewRequest), repository/AccessRequestRepository.java (reviewIfPending) · Generated from main @ 84c7760
sequenceDiagram
    autonumber
    actor FM as Facility Manager
    participant P as Request Approval Page (tab Access requests)
    participant C as :AccessRequestController
    participant S as :AccessRequestService
    participant UR as :UserRepository
    participant RR as :AccessRequestRepository
    participant AS as :AuditService
    participant NS as :InAppNotificationService

    FM->>P: Click Approve, or Reject and enter reason
    activate P
    P->>C: PATCH /api/access-requests/{id}/review {status, rejectionReason}
    activate C
    C->>S: reviewRequest(id, reviewRequest, actorEmail)
    activate S
    S->>UR: findByEmail(actorEmail)
    S->>RR: findByIdWithDetails(id)
    S->>S: Check decision and reason, reviewer is not requester, re-check members when APPROVED
    alt Failure: invalid input or rule broken
        S-->>C: IllegalArgumentException, or AccessControlException ERR_AC_006
        C-->>P: 400 Bad Request, or 403 Forbidden (self review)
    else Failure: request still PENDING but now >= startTime
        S-->>C: AccessControlException ERR_AC_012
        C-->>P: 409 Conflict
        P-->>FM: Warning banner, reload list
    else Input valid
        S->>RR: reviewIfPending(id, status, reviewer, now, reason, PENDING) [AND startTime > now]
        alt Success: 1 row updated
            S->>AS: record(ACCESS_REQUEST, APPROVE or REJECT)
            S->>NS: createForUsers(requester and members, REQUEST_APPROVED or REQUEST_REJECTED)
            S-->>C: AccessRequestResponse
            C-->>P: 200 OK
            P-->>FM: Success message, reload list
        else Failure: 0 rows, handled by someone else
            S->>RR: findByIdWithDetails(id)
            S-->>C: ConcurrentReviewException (who, new status, when), or ERR_AC_012 if still PENDING and started
            C-->>P: 409 Conflict
            P-->>FM: Warning banner, reload list
        end
    end
    deactivate S
    deactivate C
    deactivate P

    note over S,RR: No status pre-check. A request that is no longer PENDING gives 409 from the conditional UPDATE, not 400. The start-time check exists both as an early check and inside the UPDATE (race-safe)
```

### SQ-03 Finish approved access request

Source file: [`seq-finish-access-request.mmd`](seq-finish-access-request.mmd)

```mermaid
%% Title: SQ-03 Finish approved access request · Source: frontend pages/accessRequest/AccessRequestReviewPage.jsx (handleConfirmFinish), controller/AccessRequestController.java (finishRequest), service/AccessRequestService.java (finishRequest), repository/AccessRequestRepository.java (finishIfApprovedStarted) · Generated from main @ 84f5565
sequenceDiagram
    autonumber
    actor FM as Facility Manager
    participant P as Request Approval Page (tab Access requests)
    participant C as :AccessRequestController
    participant S as :AccessRequestService
    participant UR as :UserRepository
    participant RR as :AccessRequestRepository
    participant AS as :AuditService
    participant NS as :InAppNotificationService

    FM->>P: Click Finish on an APPROVED request, enter reason
    activate P
    P->>C: PATCH /api/access-requests/{id}/finish {reason}
    activate C
    C->>S: finishRequest(id, reason, actorEmail)
    activate S
    S->>RR: findByIdWithDetails(id)
    S->>UR: findByEmail(actorEmail)
    alt Failure: status is not APPROVED
        S-->>C: IllegalArgumentException
        C-->>P: 400 Bad Request
    else Failure: actor is not FACILITY_MANAGER
        S-->>C: AccessDeniedException
        C-->>P: 403 Forbidden
    else Failure: now before startTime
        S-->>C: AccessControlException ERR_AC_007
        C-->>P: 400 Bad Request (use Cancel instead)
    else Checks passed
        S->>RR: finishIfApprovedStarted(id, FINISHED, now, APPROVED, reason, actor)
        alt Success: 1 row updated
            S->>AS: record(ACCESS_REQUEST, FINISH, reason)
            S->>NS: createForUsers(requester and members, REQUEST_FINISHED)
            S-->>C: AccessRequestResponse
            C-->>P: 200 OK
        else Failure: 0 rows, changed concurrently
            S->>RR: findByIdWithDetails(id)
            S-->>C: ConcurrentReviewException
            C-->>P: 409 Conflict, list reloaded
        end
    end
    deactivate S
    deactivate C
    P-->>FM: Show result
    deactivate P
```

### SQ-04 AI Surveillance Engine calls checkEntry

Source file: [`seq-check-entry.mmd`](seq-check-entry.mmd)

```mermaid
%% Title: SQ-04 AI Surveillance Engine calls checkEntry · Source: ai-service src/core/face_matcher.py (check_camera_access), controller/AccessControlController.java (checkCameraEntry), service/AccessDecisionService.java (checkCameraEntry, checkEntry, findIncidentCoveringGuard), service/GuestAccessDecisionService.java (checkGuestEntry), service/CameraAccessLogService.java (recordAccessLog) · Generated from main @ 84f5565
sequenceDiagram
    autonumber
    actor AI as AI Surveillance Engine
    participant C as :AccessControlController
    participant S as :AccessDecisionService
    participant CR as :CameraRepository
    participant UR as :UserRepository
    participant APR as :AreaAssignedPersonnelRepository
    participant ESR as :AreaEventScheduleRepository
    participant GIR as :GuardIncidentAccessRepository
    participant RR as :AccessRequestRepository
    participant GS as :GuestAccessDecisionService
    participant LS as :CameraAccessLogService

    AI->>C: POST /api/access-control/check-entry {cameraCode, userCode} with X-Service-Key
    activate C
    C->>S: checkCameraEntry(cameraCode, userCode, at)
    activate S
    S->>CR: findByCameraCodeAndDeletedAtIsNull(cameraCode)
    S->>UR: findByUserCodeAndDeletedAtIsNull(userCode)
    alt Registered user found
        S->>S: checkEntry(userId, areaId, at): user and area active
        S->>APR: findEffectiveAt(userId, areaId, at) -> ASSIGNED_PERSONNEL
        S->>S: accessLevel at least areaAccessLevel -> ACCESS_LEVEL
        S->>S: Confidential area with isEventActive(at) -> OPEN_EVENT
        S->>ESR: existsByAreaIdAndStatusAndStartAtLessThanEqualAndEndAtGreaterThan(areaId, SCHEDULED, at, at) -> OPEN_EVENT
        S->>GIR: findLatestClaimedByUserInArea (GUARD only) -> INCIDENT_RESPONSE
        S->>RR: findCoveringRequestsForUser(userId, areaId, at, APPROVED) -> ACCESS_REQUEST
        S->>S: No source matched -> denied, source NONE, reason (out of time or level too low)
    else Guest code (UUID or GUEST-UUID)
        S->>GS: checkGuestEntry(guestId, areaId, at)
        GS-->>S: allowed with GUEST_VISIT, or denied with GuestEntryDenyReason
    else Unknown person
        S->>S: denied, source NONE, user not found
    end
    S->>LS: recordAccessLog(response, at)
    S-->>C: CheckCameraEntryResponse {allowed, source, reason, areaId}
    deactivate S
    C-->>AI: 200 OK
    deactivate C
    alt Allowed
        AI->>AI: Mark person authorized
    else Denied
        AI->>AI: Mark person unauthorized, raise alert
    end

    note over S,RR: Sources are checked in this order and the first match returns allowed. Missing or DECOMMISSIONED camera is denied without log, inactive area is denied and logged
```

### SQ-05 Create event schedule

Source file: [`seq-create-event-schedule.mmd`](seq-create-event-schedule.mmd)

```mermaid
%% Title: SQ-05 Create event schedule · Source: frontend pages/events/EventManagementPage.jsx, components/event/EventScheduleFormModal.jsx (handleSubmit), services/areaService.js (createEventSchedule), controller/AreaController.java (createEventSchedule), service/AreaService.java (createSchedule, cleanupExpiredSessions, findReasonInGroup, validateEventBudget, sendGuardScheduleNotification) · Generated from main @ 84f5565
sequenceDiagram
    autonumber
    actor FM as Facility Manager
    participant P as Event Management Page
    participant C as :AreaController
    participant S as :AreaService
    participant AR as :AreaRepository
    participant RCR as :ReasonCatalogRepository
    participant ESR as :AreaEventScheduleRepository
    participant AS as :AuditService
    participant NS as :InAppNotificationService

    FM->>P: Click Create Event, enter title, Confidential area, start, end, reason, note
    activate P
    P->>C: POST /api/areas/{id}/event-schedules {title, startAt, endAt, reasonCode, note}
    activate C
    C->>S: createSchedule(areaId, request, actorEmail)
    activate S
    S->>AR: findByIdWithLock(areaId)
    S->>S: cleanupExpiredSessions(area, now)
    S->>S: Check area active, Confidential, startAt in future, lead days, duration, title
    S->>RCR: findByActionTypeAndCode(EVENT_SCHEDULE_CREATE, reasonCode)
    S->>ESR: findByAreaIdAndStatus(areaId, SCHEDULED)
    S->>S: Check overlap with schedules and active session, max schedules, validateEventBudget
    alt Failure: any check fails
        S-->>C: AreaException (ERR_AREA_017, 022, 025, 026, 028, 032 to 039, 057)
        C-->>P: 400 Bad Request or 409 Conflict with message
        P-->>FM: Show error in form
    else Success
        S->>ESR: save(AreaEventSchedule status SCHEDULED)
        S->>AS: record(AREA_EVENT_SCHEDULE, CREATE)
        S->>NS: createForUsers(active Guards, EVENT_MODE_SCHEDULED) after commit
        S-->>C: EventScheduleResponse
        C-->>P: 201 Created
        P-->>FM: Toast, reload event list
    end
    deactivate S
    deactivate C
    deactivate P
```

### SQ-06 Review guest visit

Source file: [`seq-guest-visit-review.mmd`](seq-guest-visit-review.mmd)

```mermaid
%% Title: SQ-06 Review guest visit · Source: frontend pages/approvals/ApprovalPage.jsx, pages/guestVisit/GuestVisitReviewPage.jsx (handleApprove, handleReject), services/guestVisitService.js (reviewVisit), controller/GuestVisitController.java (review), service/GuestVisitService.java (review, recheckForApproval, notifyAfterCommit) · Generated from main @ 84f5565
sequenceDiagram
    autonumber
    actor FM as Facility Manager
    participant P as Request Approval Page (tab Guest visits)
    participant C as :GuestVisitController
    participant S as :GuestVisitService
    participant UR as :UserRepository
    participant GVR as :GuestVisitRepository
    participant AS as :AuditService
    participant NS as :InAppNotificationService

    FM->>P: Click Approve, or Reject and enter reason
    activate P
    P->>C: PATCH /api/guest-visits/{id}/review {version, decision, reason}
    activate C
    C->>S: review(id, request, actorEmail)
    activate S
    S->>UR: findByEmail(actorEmail)
    S->>S: Check version present, decision APPROVED or REJECTED, reason length
    S->>GVR: findByIdForUpdate(id) (row lock)
    S->>S: Check reviewer is not host, version matches, status PENDING, now before startTime, recheckForApproval
    alt Failure: a check fails
        S-->>C: GuestException (ERR_GUEST_015, 016, 017, 019, 021, 022, 023, 024)
        C-->>P: 400, 403 or 409 with error code
        P-->>FM: Show error, reload list on ERR_GUEST_016
    else Success
        S->>GVR: save(visit APPROVED or REJECTED, version + 1)
        S->>AS: record(GUEST_VISIT, APPROVE or REJECT)
        S->>NS: After commit: GUEST_VISIT_APPROVED to host and GUEST_PHOTO_REQUIRED to Administrators, or GUEST_VISIT_REJECTED to host
        S-->>C: GuestVisitResponse
        C-->>P: 200 OK
        P-->>FM: Close popup, reload list
    end
    deactivate S
    deactivate C
    deactivate P
```

### CD-01 Access control classes, part 1 of 2: domain model

Source file: [`class-access-control.mmd`](class-access-control.mmd)

```mermaid
%% Title: CD-01 Access control classes, part 1 of 2: domain model · Source: entity/AccessRequest.java, AccessRequestMember.java, User.java, Area.java, AreaAssignedPersonnel.java, AreaEventSchedule.java, AreaEventSession.java, GuestVisit.java, Guest.java, GuestFaceEmbedding.java, enums/* · Generated from main @ 84f5565
classDiagram
    direction LR

    class User {
        +UUID id
        +String userCode
        +String fullName
        +Role role
        +Integer accessLevel
        +Boolean isActive
        +OffsetDateTime deletedAt
    }

    class Area {
        +UUID id
        +String name
        +AreaLevel areaLevel
        +Integer areaAccessLevel
        +Boolean openToMembers
        +OffsetDateTime openUntil
        +Boolean isActive
        +OffsetDateTime deletedAt
        +Long version
        +isEventActive(OffsetDateTime now) boolean
    }

    class AccessRequest {
        +UUID id
        +RequestType requestType
        +String purpose
        +OffsetDateTime startTime
        +OffsetDateTime endTime
        +RequestStatus status
        +String rejectionReason
        +CancelSource cancelSource
        +String cancelReason
        +String finishReason
    }

    class AccessRequestMember {
        +UUID id
        +Boolean sponsored
    }

    class AreaAssignedPersonnel {
        +UUID id
        +OffsetDateTime validFrom
        +OffsetDateTime validTo
        +OffsetDateTime revokedAt
        +String revokeReason
    }

    class AreaEventSchedule {
        +UUID id
        +String title
        +OffsetDateTime startAt
        +OffsetDateTime endAt
        +AreaEventScheduleStatus status
        +String reasonCode
        +String failReason
    }

    class AreaEventSession {
        +UUID id
        +OffsetDateTime startedAt
        +OffsetDateTime plannedEnd
        +OffsetDateTime actualEnd
        +UUID scheduleId
    }

    class GuestVisit {
        +UUID id
        +String purpose
        +OffsetDateTime startTime
        +OffsetDateTime endTime
        +GuestVisitStatus status
        +Long version
    }

    class Guest {
        +UUID id
        +String fullName
        +String organization
        +GuestBiometricStatus biometricStatus
        +String consentNoticeVersion
        +OffsetDateTime anonymizedAt
    }

    class GuestFaceEmbedding {
        +UUID guestId
        +String embedding
        +OffsetDateTime expiresAt
    }

    class RequestStatus {
        <<enumeration>>
        PENDING
        APPROVED
        REJECTED
        CANCELLED
        EXPIRED
        FINISHED
    }

    class GuestVisitStatus {
        <<enumeration>>
        PENDING
        APPROVED
        REJECTED
        CANCELLED
        REVOKED
        EXPIRED
        COMPLETED
    }

    class AreaEventScheduleStatus {
        <<enumeration>>
        SCHEDULED
        STARTED
        CANCELLED
        FAILED
        COMPLETED
        ENDED_EARLY
    }

    class AssignedPersonnelStatus {
        <<enumeration>>
        ACTIVE
        UPCOMING
        EXPIRED
        REVOKED
    }

    class AreaLevel {
        <<enumeration>>
        PUBLIC
        CONFIDENTIAL
        HIGHLY_CONFIDENTIAL
    }

    class GuestBiometricStatus {
        <<enumeration>>
        NO_PHOTO
        PHOTO_READY
        PHOTO_ONLY
        DELETED
    }

    AccessRequest "*" --> "1" Area : area
    AccessRequest "*" --> "1" User : requester
    AccessRequest "*" --> "0..1" User : reviewer
    AccessRequest "1" *-- "*" AccessRequestMember : members
    AccessRequestMember "*" --> "1" User : user
    AreaAssignedPersonnel "*" --> "1" Area : area
    AreaAssignedPersonnel "*" --> "1" User : user
    AreaEventSchedule "*" --> "1" Area : area
    AreaEventSchedule "*" --> "0..1" AreaEventSession : session
    AreaEventSession "*" --> "1" Area : area
    AreaEventSession ..> AreaEventSchedule : scheduleId
    GuestVisit "*" --> "1" User : host
    GuestVisit "*" --> "1..*" Area : areas
    GuestVisit "1" *-- "1..*" Guest : guests
    GuestFaceEmbedding ..> Guest : guestId

    AccessRequest ..> RequestStatus
    GuestVisit ..> GuestVisitStatus
    Guest ..> GuestBiometricStatus
    AreaEventSchedule ..> AreaEventScheduleStatus
    AreaAssignedPersonnel ..> AssignedPersonnelStatus : computed, not stored
    Area ..> AreaLevel
```

### CD-01 Access control classes, part 2 of 2: controllers, services, jobs

Source file: [`class-access-control-services.mmd`](class-access-control-services.mmd)

```mermaid
%% Title: CD-01 Access control classes, part 2 of 2: controllers, services, jobs · Source: controller/AccessRequestController.java, GuestVisitController.java, AreaController.java, AreaAssignedPersonnelController.java, AccessControlController.java, service/AccessRequestService.java, GuestVisitService.java, GuestVisitJobService.java, AreaService.java, AreaAssignedPersonnelService.java, AccessDecisionService.java, GuestAccessDecisionService.java, UserService.java, scheduler/AreaEventModeScheduler.java, GuestVisitScheduler.java · Generated from main @ 84c7760
classDiagram
    direction LR

    class AccessRequestController {
        +createIndividualRequest()
        +createGroupRequest()
        +reviewRequest()
        +cancelRequest()
        +cancelApprovedRequest()
        +finishRequest()
    }
    class GuestVisitController {
        +create()
        +cancel()
        +review()
        +revoke()
    }
    class AreaController {
        +update()
        +deactivate()
        +restore()
        +updateEventMode()
        +createEventSchedule()
    }
    class AreaAssignedPersonnelController {
        +create()
        +revoke()
    }
    class AccessControlController {
        +checkCameraEntry()
    }

    class AccessRequestService {
        +createIndividualRequest()
        +createGroupRequest()
        +reviewRequest()
        +cancelRequest()
        +finishRequest()
        +cancelApprovedRequest()
        +expireOverdueRequests()
        +finishElapsedApprovedRequests()
        +cancelActiveRequestsOfRequester()
    }
    class GuestVisitService {
        +create()
        +review()
        +cancel()
        +revoke()
        +closeForAreaDeactivation()
    }
    class GuestVisitJobService {
        +runAll()
        +expirePendingVisits()
        +completeApprovedVisits()
        +deleteDueBiometrics()
        +anonymizeGuests()
    }
    class AreaService {
        +update()
        +deactivate()
        +restore()
        +updateEventMode()
        +createSchedule()
        +updateSchedule()
        +cancelSchedule()
        +processExpiredEventSessions()
        +processScheduledEventActivations()
    }
    class AreaAssignedPersonnelService {
        +create()
        +revoke()
        +revokeAllOfUser()
        computeStatus()$
    }
    class AccessDecisionService {
        +checkCameraEntry()
        +checkEntry()
    }
    class GuestAccessDecisionService {
        +checkGuestEntry()
    }
    class UserService {
        +toggleActive()
        +softDelete()
        +deleteBatch()
    }
    class AreaEventModeScheduler {
        <<job>>
        +tick()
    }
    class GuestVisitScheduler {
        <<job>>
        +tick()
    }

    AccessRequestController --> AccessRequestService
    GuestVisitController --> GuestVisitService
    AreaController --> AreaService
    AreaAssignedPersonnelController --> AreaAssignedPersonnelService
    AccessControlController --> AccessDecisionService

    AccessDecisionService --> GuestAccessDecisionService : guest branch
    AreaService --> GuestVisitService : closeForAreaDeactivation
    AreaService ..> AreaAssignedPersonnelService : computeStatus
    AccessRequestService ..> AreaService : requestIneligibility
    UserService --> AccessRequestService : cancelActiveRequestsOfRequester
    UserService --> AreaAssignedPersonnelService : revokeAllOfUser
    GuestVisitJobService --> GuestVisitService : deleteAllBiometrics
    AreaEventModeScheduler --> AreaService : every minute
    GuestVisitScheduler --> GuestVisitJobService : every minute
```

### ER-01 Access control tables

Source file: [`erd-access-control.mmd`](erd-access-control.mmd)

```mermaid
%% Title: ER-01 Access control tables · Source: db/migration V19, V37, V42, V45, V54_1, V55, V55_1, V56, V58, V60, V62, V73, V74, V76, V83, V87, V88 (latest V89) · Generated from main @ 84f5565
erDiagram
    users {
        uuid id PK
        varchar user_code
        varchar full_name
        varchar role
        smallint access_level
        boolean is_active
        timestamptz deleted_at
    }

    floors {
        uuid id PK
        uuid building_id FK
        varchar name
        boolean is_active
    }

    areas {
        uuid id PK
        uuid floor_id FK
        varchar name
        varchar area_level "PUBLIC, CONFIDENTIAL, HIGHLY_CONFIDENTIAL"
        smallint area_access_level "1 to 3, fixed by type"
        boolean open_to_members
        timestamptz open_until
        boolean is_active
        timestamptz deleted_at
        bigint version
    }

    cameras {
        uuid id PK
        uuid area_id FK "NOT NULL since V76"
        varchar camera_code
        varchar status
    }

    access_requests {
        uuid id PK
        uuid area_id FK
        uuid requester_id FK
        uuid reviewer_id FK
        uuid cancelled_by FK
        uuid finished_by FK
        varchar request_type "INDIVIDUAL, GROUP"
        timestamptz start_time
        timestamptz end_time
        varchar status "PENDING ... FINISHED"
        varchar cancel_source "USER, SYSTEM, STAFF"
        text cancel_reason
        varchar finish_reason
    }

    access_request_members {
        uuid id PK
        uuid access_request_id FK
        uuid user_id FK
        boolean sponsored
    }

    area_assigned_personnel {
        uuid id PK
        uuid area_id FK
        uuid user_id FK
        uuid created_by FK
        uuid revoked_by FK
        timestamptz valid_from
        timestamptz valid_to "NULL = unlimited"
        timestamptz revoked_at "status computed, not stored"
        text revoke_reason
    }

    area_event_sessions {
        uuid id PK
        uuid area_id FK
        uuid started_by FK
        uuid ended_by FK
        uuid schedule_id FK
        timestamptz started_at
        timestamptz planned_end
        timestamptz actual_end
    }

    area_event_schedules {
        uuid id PK
        uuid area_id FK
        uuid session_id FK
        uuid created_by FK
        varchar title
        timestamptz start_at
        timestamptz end_at
        varchar status "SCHEDULED ... ENDED_EARLY"
        varchar reason_code
    }

    guest_visits {
        uuid id PK
        uuid host_id FK
        uuid reviewed_by FK
        uuid revoked_by FK
        timestamptz start_time
        timestamptz end_time
        varchar status "PENDING ... COMPLETED"
        bigint version
    }

    guest_visit_areas {
        uuid visit_id PK, FK
        uuid area_id PK, FK
    }

    guests {
        uuid id PK
        uuid visit_id FK
        uuid consent_confirmed_by FK
        varchar full_name
        varchar biometric_status "NO_PHOTO, PHOTO_READY, PHOTO_ONLY, DELETED"
        varchar consent_notice_version
        timestamptz anonymized_at
    }

    guest_face_embeddings {
        uuid guest_id PK, FK
        vector embedding "vector 512"
        timestamptz expires_at
    }

    camera_access_logs {
        uuid id PK
        uuid area_id FK
        uuid user_id FK
        varchar camera_code
        varchar access_result
        varchar access_source
        uuid source_reference_id
        timestamptz logged_at
    }

    audit_logs {
        uuid id PK
        uuid area_id FK
        uuid subject_user_id FK
        uuid changed_by FK
        varchar target_type
        varchar action
        varchar target_id
        jsonb old_value
        jsonb new_value
        uuid correlation_id
    }

    floors |o--o{ areas : "floor_id"
    areas ||--o{ cameras : "area_id"

    areas ||--o{ access_requests : "area_id"
    users ||--o{ access_requests : "requester_id"
    users |o--o{ access_requests : "reviewer_id"
    access_requests ||--o{ access_request_members : "access_request_id"
    users ||--o{ access_request_members : "user_id"

    areas ||--o{ area_assigned_personnel : "area_id"
    users ||--o{ area_assigned_personnel : "user_id"

    areas ||--o{ area_event_sessions : "area_id"
    areas ||--o{ area_event_schedules : "area_id"
    area_event_sessions |o--o{ area_event_schedules : "session_id"
    area_event_schedules |o--o{ area_event_sessions : "schedule_id"

    users ||--o{ guest_visits : "host_id"
    guest_visits ||--o{ guest_visit_areas : "visit_id"
    areas ||--o{ guest_visit_areas : "area_id"
    guest_visits ||--o{ guests : "visit_id"
    guests ||--o| guest_face_embeddings : "guest_id"

    areas ||--o{ camera_access_logs : "area_id"
    users |o--o{ camera_access_logs : "user_id"
    areas |o--o{ audit_logs : "area_id"
    users |o--o{ audit_logs : "subject_user_id"
```
