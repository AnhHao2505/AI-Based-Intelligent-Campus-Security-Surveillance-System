# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users
- **Primary:** Campus Security Guards (Internal Guards) stationed in control rooms or campus gate posts, responsible for live video monitoring, immediate incident triage (sub-3-second intrusion/loitering alerts), map-based guard dispatch, and validating or dismissing AI detections.
- **Secondary:**
  - **Facility Managers:** Configuring restricted areas, setting access schedules, reviewing violation statistics, and monitoring campus safety dashboards.
  - **System Administrators:** Managing user accounts, role-based access control (RBAC), AI detection parameters, and face dataset enrollments.
  - **Authorized Staff / Lecturers / Students:** Viewing personal access history and submitting access requests for restricted campus zones.

## Product Purpose
Develop an AI-based intelligent campus security surveillance system for restricted area access control at FPT University (Tân Uyên Campus). The system replaces manual, fatigue-prone multi-stream observation with automated computer vision (human detection, face recognition, perimeter intrusion, and loitering detection), alerting security personnel within 3 seconds to prevent unauthorized entry and maintain historical auditability.

## Positioning
An integrated, low-latency campus surveillance ecosystem tailored to university security: combining real-time multi-camera RTSP streaming, edge AI detection (YOLO + InsightFace), interactive campus spatial mapping (MapLibre GL), and single-shot/bulk facial dataset management into a cohesive, role-based control console.

## Operating Context
- **Control Room & Gate Environments:** High-density desktop and multi-monitor setups where security personnel continuously observe live camera feeds, live event logs, and spatial maps.
- **Incident Scenarios:** Unauthorized presence in laboratories, server rooms, data centers, electrical control rooms, and construction zones, especially outside authorized schedules.
- **Workflows:** Real-time alert notification -> 1-click video/snapshot playback & verification -> guard action or incident dismissal -> persistent audit logging.

## Capabilities and Constraints
- **Capabilities:**
  - Live RTSP/HLS/WebRTC camera streaming with multi-grid layouts and playback recording.
  - Real-time AI inference pipeline detecting unknown individuals, restricted zone incursions, and loitering.
  - Sub-3-second end-to-end alert latency from camera detection to guard console.
  - Interactive campus map with camera markers, zone status overlays, and incident pulse animations.
  - Face dataset management supporting single photo enrollment and bulk ZIP upload with automatic HEIC/HEIF conversion.
  - Granular RBAC (Admin, Guard, Facility Manager, Normal User).
- **Constraints:**
  - High availability requirement (99.5% minimum uptime).
  - Web portal optimized for modern desktop browsers (Vite + React 19 + Tailwind CSS).
  - Secure storage of video evidence and face embeddings (PostgreSQL + MinIO S3).

## Brand Commitments
- **Name:** FPTU Campus Security Surveillance System (FA26SE040 - Tân Uyên Campus).
- **Voice:** Professional, mission-critical, authoritative, and operational.
- **Tone:** High-urgency alert legibility without visual chaos; calm, clear status hierarchy.

## Evidence on Hand
- System operational guide: `SYSTEM_GUIDE.md`
- Capstone Project specification: `docs/Phieu_FA26SE040.txt`
- Entity Relationship Diagram: `docs/ERD.txt`
- Architecture & workflow diagrams: `docs/diagrams/`
- Prior UI audit and primary theme audit: `docs/ui-audit.md`, `docs/theme-primary-audit.md`
- Video recording & streaming pipelines: `ai-service/src/pipeline/stream_recorder.py`, `backend/src/main/java/com/fa26se040/icss/`

## Product Principles
1. **Speed to Action (3-Second SLA):** Alerts must demand zero cognitive friction; guards must comprehend who, where, and what within seconds.
2. **Operational Calm & High Density:** Prioritize scannability, clear typography, and clean contrast over decorative flair. Normal operations stay quiet; violations demand immediate attention.
3. **High-Fidelity Spatial & Visual Context:** Every alert is married to video evidence, timestamps, camera identity, and campus map location.
4. **Resilient Verification Loop:** Provide swift, clear affordances for security officers to confirm, dismiss, or escalate AI findings with audit trails.
