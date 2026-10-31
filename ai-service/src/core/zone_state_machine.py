import time
from enum import Enum
from typing import Optional

class ZoneState(str, Enum):
    IDLE = "IDLE"
    MOTION_ACTIVE = "MOTION_ACTIVE"
    ZONE_OCCUPIED = "ZONE_OCCUPIED"
    COOLDOWN = "COOLDOWN"

class ZoneStateMachine:
    """
    Máy trạng thái quản lý sự hiện diện trong vùng an ninh (State Latch Mechanism):
    - IDLE: Vùng trống, không có chuyển động -> Tiết kiệm 100% tải Deep Learning
    - MOTION_ACTIVE: Chuyển động vừa xuất hiện -> Kích hoạt YOLO26 tức thì
    - ZONE_OCCUPIED: Đã xác nhận có người hoặc chuyển động liên tục -> CHỐT GIỮ (LATCH),
      kể cả khi đối tượng đứng yên / ngồi nấp
    - COOLDOWN: Đối tượng vừa rời đi -> Đếm lùi 5s an toàn trước khi về IDLE
    """

    def __init__(
        self,
        cooldown_seconds: float = 5.0,
        motion_to_occupied_seconds: float = 1.0
    ):
        self.cooldown_seconds = cooldown_seconds
        self.motion_to_occupied_seconds = motion_to_occupied_seconds

        self._state: ZoneState = ZoneState.IDLE
        self._motion_start_time: Optional[float] = None
        self._cooldown_start_time: Optional[float] = None
        self._last_presence_time: Optional[float] = None

    @property
    def state(self) -> ZoneState:
        return self._state

    @property
    def is_occupied(self) -> bool:
        """Trả về True nếu vùng đang có người hoặc đang trong trạng thái chốt giữ / cooldown"""
        return self._state in (ZoneState.ZONE_OCCUPIED, ZoneState.COOLDOWN, ZoneState.MOTION_ACTIVE)

    @property
    def should_run_deep_learning(self) -> bool:
        """Quyết định có cần đánh thức YOLO26 và Face Recognition hay không"""
        return self._state != ZoneState.IDLE

    def reset(self):
        """Khởi tạo lại trạng thái về IDLE"""
        self._state = ZoneState.IDLE
        self._motion_start_time = None
        self._cooldown_start_time = None
        self._last_presence_time = None

    def update(
        self,
        has_motion: bool,
        person_in_roi: bool,
        current_time: Optional[float] = None
    ) -> ZoneState:
        """
        Cập nhật trạng thái máy dựa trên tín hiệu từ OpenCV Motion và YOLO26:
        - has_motion: Tín hiệu chuyển động từ OpenCV MotionDetector
        - person_in_roi: Tín hiệu có class person trong ROI từ YOLO26
        """
        now = current_time if current_time is not None else time.time()

        # Bất cứ khi nào có người trong ROI, cập nhật mốc thời gian hiện diện
        if person_in_roi or has_motion:
            self._last_presence_time = now

        # ==============================================================
        # 1. TRẠNG THÁI IDLE (Phòng trống)
        # ==============================================================
        if self._state == ZoneState.IDLE:
            if has_motion or person_in_roi:
                self._state = ZoneState.MOTION_ACTIVE
                self._motion_start_time = now
                # Nếu ngay frame đầu YOLO đã thấy rõ person -> Latch ngay lập tức
                if person_in_roi:
                    self._state = ZoneState.ZONE_OCCUPIED

        # ==============================================================
        # 2. TRẠNG THÁI MOTION_ACTIVE (Vừa có chuyển động)
        # ==============================================================
        elif self._state == ZoneState.MOTION_ACTIVE:
            if person_in_roi:
                # YOLO xác nhận có người -> Chuyển sang ZONE_OCCUPIED
                self._state = ZoneState.ZONE_OCCUPIED
            elif has_motion:
                # Chuyển động liên tục vượt ngưỡng thời gian -> Kẻ ngụy trang / Chốt giữ
                if self._motion_start_time and (now - self._motion_start_time) >= self.motion_to_occupied_seconds:
                    self._state = ZoneState.ZONE_OCCUPIED
            else:
                # Chuyển động tắt ngay (< threshold) và YOLO không thấy người -> Nhiễu thoáng qua, về IDLE
                self._state = ZoneState.IDLE
                self._motion_start_time = None

        # ==============================================================
        # 3. TRẠNG THÁI ZONE_OCCUPIED (Chốt giữ theo dõi người)
        # ==============================================================
        elif self._state == ZoneState.ZONE_OCCUPIED:
            # Nếu vẫn còn person hoặc motion trong ROI -> Giữ nguyên trạng thái
            if person_in_roi or has_motion:
                self._cooldown_start_time = None
            else:
                # Không còn tín hiệu người hay chuyển động -> Chuyển sang COOLDOWN
                self._state = ZoneState.COOLDOWN
                self._cooldown_start_time = now

        # ==============================================================
        # 4. TRẠNG THÁI COOLDOWN (Đệm an toàn trước khi ngủ)
        # ==============================================================
        elif self._state == ZoneState.COOLDOWN:
            if person_in_roi or has_motion:
                # Xuất hiện lại người/chuyển động trong 5s cooldown -> Quay lại ZONE_OCCUPIED ngay
                self._state = ZoneState.ZONE_OCCUPIED
                self._cooldown_start_time = None
            else:
                # Đếm lùi hết thời gian cooldown -> Phòng an toàn, chuyển về IDLE
                if self._cooldown_start_time and (now - self._cooldown_start_time) >= self.cooldown_seconds:
                    self._state = ZoneState.IDLE
                    self._cooldown_start_time = None
                    self._motion_start_time = None

        return self._state
