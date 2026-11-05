import re
import ipaddress
from urllib.parse import urlsplit


def mask_rtsp_url(url: str) -> str:
    """Che giấu mật khẩu trong URL RTSP trước khi log để đảm bảo an ninh thông tin"""
    if not url:
        return ""
    try:
        # Regex che mật khẩu giữa ':' và '@' trong phần userinfo của URL
        return re.sub(r"((?:rtsp|rtsps)://[^:@/\s]+):([^@/\s]+)@", r"\1:***@", url)
    except Exception:
        return "[MASKED_URL]"


def validate_snapshot_rtsp_url(url: str) -> bool:
    """
    Kiểm tra tính hợp lệ và an toàn của RTSP URL cho endpoint snapshot (chống SSRF):
    - Chỉ chấp nhận scheme rtsp hoặc rtsps
    - Chặn các địa chỉ IP metadata đám mây nguy hiểm (169.254.169.254, fd00:ec2::254...)
    """
    if not url or not isinstance(url, str):
        return False
    url_stripped = url.strip()
    try:
        parts = urlsplit(url_stripped)
        if parts.scheme.lower() not in ("rtsp", "rtsps"):
            return False
        hostname = parts.hostname
        if not hostname:
            return False

        # Chặn cloud metadata addresses
        blocked_hosts = {
            "169.254.169.254",
            "metadata.google.internal",
            "instance-data",
        }
        if hostname.lower() in blocked_hosts:
            return False

        try:
            ip = ipaddress.ip_address(hostname)
            # Chặn link-local unicast (169.254.0.0/16 và fe80::/10)
            if ip.is_link_local:
                return False
        except ValueError:
            pass

        return True
    except Exception:
        return False


import logging


class UvicornTokenMaskingFilter(logging.Filter):
    """
    Điểm 11: Che giấu giá trị tham số `token=` trong access log của Uvicorn
    nhằm ngăn rò rỉ HMAC stream token của /api/v1/stream/live-annotated.
    """
    TOKEN_REGEX = re.compile(r"([?&]token=)[^&\s'\"]+", re.IGNORECASE)
    RAW_TOKEN_REGEX = re.compile(r"(token=)[^&\s'\"]+", re.IGNORECASE)

    def filter(self, record: logging.LogRecord) -> bool:
        if record.args:
            if isinstance(record.args, tuple):
                new_args = []
                for arg in record.args:
                    if isinstance(arg, str) and "token=" in arg.lower():
                        new_args.append(self.TOKEN_REGEX.sub(r"\1***", arg))
                    else:
                        new_args.append(arg)
                record.args = tuple(new_args)
            elif isinstance(record.args, dict):
                new_args = {}
                for k, v in record.args.items():
                    if isinstance(v, str) and "token=" in v.lower():
                        new_args[k] = self.TOKEN_REGEX.sub(r"\1***", v)
                    else:
                        new_args[k] = v
                record.args = new_args

        if isinstance(record.msg, str) and "token=" in record.msg.lower():
            record.msg = self.RAW_TOKEN_REGEX.sub(r"\1***", record.msg)

        return True


def setup_uvicorn_access_log_filter():
    """Gắn UvicornTokenMaskingFilter vào logger uvicorn.access"""
    u_logger = logging.getLogger("uvicorn.access")
    if not any(isinstance(f, UvicornTokenMaskingFilter) for f in u_logger.filters):
        u_logger.addFilter(UvicornTokenMaskingFilter())
