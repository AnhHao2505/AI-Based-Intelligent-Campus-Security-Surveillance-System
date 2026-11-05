import os
import sys
import time
import logging
from typing import Optional

# Bật ANSI escape sequence trên Windows Terminal / CMD
if sys.platform == "win32":
    try:
        os.system("")
    except Exception:
        pass

# Bảng màu ANSI cho terminal
class TerminalColors:
    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    
    RED = "\033[31m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    BLUE = "\033[34m"
    MAGENTA = "\033[35m"
    CYAN = "\033[36m"
    WHITE = "\033[37m"
    
    BRIGHT_RED = "\033[91m"
    BRIGHT_GREEN = "\033[92m"
    BRIGHT_YELLOW = "\033[93m"
    BRIGHT_BLUE = "\033[94m"
    BRIGHT_MAGENTA = "\033[95m"
    BRIGHT_CYAN = "\033[96m"


class AITerminalFormatter(logging.Formatter):
    """
    Formatter chuyên biệt cho terminal AI Service:
    - Hiển thị thời gian mili-giây chính xác
    - Phân màu trực quan theo Level (INFO: xanh, WARN: vàng, ERROR: đỏ)
    - Tô sáng định danh Module / Hàm logic (Cyan / Bold)
    - Dễ đọc, dễ theo dõi hàm nào & logic nào đang chạy
    """
    LEVEL_COLORS = {
        logging.DEBUG: TerminalColors.BLUE,
        logging.INFO: TerminalColors.BRIGHT_GREEN,
        logging.WARNING: TerminalColors.BRIGHT_YELLOW,
        logging.ERROR: TerminalColors.BRIGHT_RED,
        logging.CRITICAL: TerminalColors.BOLD + TerminalColors.BRIGHT_RED,
    }

    def __init__(self, use_colors: bool = True):
        super().__init__(datefmt="%Y-%m-%d %H:%M:%S")
        self.use_colors = use_colors and not os.environ.get("NO_COLOR")

    def format(self, record: logging.LogRecord) -> str:
        # Thời gian
        record_time = time.strftime(self.datefmt, time.localtime(record.created))
        msecs = int(record.msecs)
        time_str = f"{record_time}.{msecs:03d}"
        
        # Tên module / tag rút gọn
        name = record.name
        if name.startswith("src."):
            name = name[4:]
        tag = f"[{name}]"
        if len(tag) > 26:
            tag = tag[:23] + "...]"
        tag_str = f"{tag:<26}"

        level_str = f"{record.levelname:<8}"
        msg = record.getMessage()

        if self.use_colors:
            lvl_color = self.LEVEL_COLORS.get(record.levelno, TerminalColors.WHITE)
            c_time = f"{TerminalColors.DIM}{time_str}{TerminalColors.RESET}"
            c_level = f"{lvl_color}{level_str}{TerminalColors.RESET}"
            c_tag = f"{TerminalColors.BRIGHT_CYAN}{tag_str}{TerminalColors.RESET}"
            return f"{c_time} | {c_level} | {c_tag} | {msg}"
        else:
            return f"{time_str} | {level_str} | {tag_str} | {msg}"


_logging_initialized = False

def setup_ai_logging(
    level: Optional[str] = None,
    use_colors: bool = True,
    force_reinit: bool = False
) -> logging.Logger:
    """
    Khởi tạo hệ thống ghi log tập trung trên Terminal cho AI Service.
    Đảm bảo:
    - Mọi lệnh logger.info, logger.warning, logger.error đều xuất hiện trên terminal
    - Hỗ trợ ký tự tiếng Việt UTF-8 không bị lỗi font trên Windows
    - Tự động lọc các log rác bên ngoài (PIL, urllib3) để terminal sạch đẹp
    """
    global _logging_initialized
    if _logging_initialized and not force_reinit:
        return logging.getLogger("ai_service")

    # Đảm bảo UTF-8 cho stdout trên Windows
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass

    log_level_str = (level or os.environ.get("AI_LOG_LEVEL", "INFO")).upper()
    log_level = getattr(logging, log_level_str, logging.INFO)

    root_logger = logging.getLogger()
    root_logger.setLevel(log_level)

    # Xóa các handler cũ nếu có
    for h in list(root_logger.handlers):
        root_logger.removeHandler(h)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(log_level)
    handler.setFormatter(AITerminalFormatter(use_colors=use_colors))
    root_logger.addHandler(handler)

    # Đặt mức log nhẹ cho các thư viện thứ ba tránh làm rác màn hình terminal
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("botocore").setLevel(logging.WARNING)
    logging.getLogger("s3transfer").setLevel(logging.WARNING)
    logging.getLogger("PIL").setLevel(logging.WARNING)
    logging.getLogger("matplotlib").setLevel(logging.WARNING)
    logging.getLogger("asyncio").setLevel(logging.WARNING)

    # Uvicorn loggers
    u_access = logging.getLogger("uvicorn.access")
    u_access.handlers = [handler]
    u_access.propagate = False
    
    u_error = logging.getLogger("uvicorn.error")
    u_error.handlers = [handler]
    u_error.propagate = False

    _logging_initialized = True
    ai_logger = logging.getLogger("ai_service")
    ai_logger.info("⚡ [AI Service] Đã kích hoạt hệ sinh thái ghi log Terminal thời gian thực (Level: %s)", log_level_str)
    return ai_logger


def log_ai_step(component: str, function_name: str, message: str, level: str = "info"):
    """
    Hàm tiện ích ghi log nghiệp vụ AI trực quan:
    Hiển thị rõ: [Component] ⚙️ [function_name] Nội dung logic đã chạy.
    """
    log = logging.getLogger(f"AI:{component}")
    fn = getattr(log, level.lower(), log.info)
    fn(f"⚙️ [{function_name}] {message}")
