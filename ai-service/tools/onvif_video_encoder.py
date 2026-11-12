"""
Xem / sửa cấu hình mã hoá video của camera IP qua ONVIF (không cần app của hãng).

Dùng khi công cụ như ONVIF Device Manager không đọc được profile (camera trả codec H265 - ngoài chuẩn ONVIF Media 1).

Tài khoản camera: mặc định lấy từ URL RTSP nguồn của path trong MediaMTX (vd cam-002),
hoặc truyền --host/--user/--password. Mật khẩu không bao giờ được in ra.

Chạy từ thư mục ai-service:
  .venv\\Scripts\\python tools\\onvif_video_encoder.py show
  .venv\\Scripts\\python tools\\onvif_video_encoder.py set --encoding H264 --width 1920 --height 1080 --fps 20 --bitrate 4096 --dry-run
  .venv\\Scripts\\python tools\\onvif_video_encoder.py set --encoding H264 --width 1920 --height 1080 --fps 20 --bitrate 4096
Khôi phục cấu hình gốc của camera IPC-F52P (đọc ngày 12/11/2026):
  .venv\\Scripts\\python tools\\onvif_video_encoder.py set --encoding H265 --width 2688 --height 1664 --fps 20 --bitrate 2048 --gov 80
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

import requests

NS = {
    "s": "http://www.w3.org/2003/05/soap-envelope",
    "tt": "http://www.onvif.org/ver10/schema",
    "trt": "http://www.onvif.org/ver10/media/wsdl",
    "tds": "http://www.onvif.org/ver10/device/wsdl",
}
for _p, _u in NS.items():
    ET.register_namespace(_p, _u)
TT = "{%s}" % NS["tt"]


class Onvif:
    def __init__(self, host: str, user: str, password: str, port: int = 80):
        self.base = f"http://{host}:{port}"
        self.user, self.password = user, password
        self.media_path = "/onvif/media_service"

    def _security(self) -> str:
        nonce = os.urandom(16)
        created = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        digest = base64.b64encode(hashlib.sha1(nonce + created.encode() + self.password.encode()).digest()).decode()
        return (
            '<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">'
            f"<UsernameToken><Username>{self.user}</Username>"
            '<Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">'
            f"{digest}</Password>"
            '<Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-soap-message-security-1.0#Base64Binary">'
            f"{base64.b64encode(nonce).decode()}</Nonce>"
            f'<Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">{created}</Created>'
            "</UsernameToken></Security>"
        )

    def call(self, path: str, body_xml: str) -> ET.Element:
        env = (f'<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="{NS["s"]}">'
               f"<s:Header>{self._security()}</s:Header><s:Body>{body_xml}</s:Body></s:Envelope>")
        r = requests.post(self.base + path, data=env.encode("utf-8"),
                          headers={"Content-Type": "application/soap+xml; charset=utf-8"}, timeout=15)
        root = ET.fromstring(r.content)
        fault = root.find(".//s:Fault", NS)
        if r.status_code != 200 or fault is not None:
            reason = "".join(fault.itertext()).strip() if fault is not None else r.text[:300]
            raise RuntimeError(f"ONVIF lỗi HTTP {r.status_code}: {reason}")
        return root.find("s:Body", NS)

    def discover_media_path(self):
        body = self.call("/onvif/device_service",
                         f'<tds:GetCapabilities xmlns:tds="{NS["tds"]}"><tds:Category>Media</tds:Category></tds:GetCapabilities>')
        xaddr = body.find(".//tt:Media/tt:XAddr", NS)
        if xaddr is not None and xaddr.text:
            self.media_path = urllib.parse.urlparse(xaddr.text).path

    def device_info(self) -> str:
        body = self.call("/onvif/device_service", f'<tds:GetDeviceInformation xmlns:tds="{NS["tds"]}"/>')
        g = lambda t: (body.find(f".//tds:{t}", NS).text if body.find(f".//tds:{t}", NS) is not None else "-")
        return f"{g('Manufacturer')} {g('Model')} (firmware {g('FirmwareVersion')})"

    def encoder_configs(self):
        body = self.call(self.media_path, f'<trt:GetVideoEncoderConfigurations xmlns:trt="{NS["trt"]}"/>')
        return body.findall(".//trt:Configurations", NS)

    def encoder_config(self, token: str) -> ET.Element:
        body = self.call(self.media_path,
                         f'<trt:GetVideoEncoderConfiguration xmlns:trt="{NS["trt"]}">'
                         f"<trt:ConfigurationToken>{token}</trt:ConfigurationToken></trt:GetVideoEncoderConfiguration>")
        cfg = body.find(".//trt:Configuration", NS)
        if cfg is None:
            raise RuntimeError(f"Không tìm thấy cấu hình encoder {token}")
        return cfg

    def encoder_options(self, token: str) -> ET.Element:
        return self.call(self.media_path,
                         f'<trt:GetVideoEncoderConfigurationOptions xmlns:trt="{NS["trt"]}">'
                         f"<trt:ConfigurationToken>{token}</trt:ConfigurationToken></trt:GetVideoEncoderConfigurationOptions>")

    def set_encoder_config(self, cfg: ET.Element):
        xml = ET.tostring(cfg, encoding="unicode")
        self.call(self.media_path,
                  f'<trt:SetVideoEncoderConfiguration xmlns:trt="{NS["trt"]}" xmlns:tt="{NS["tt"]}">{xml}'
                  "<trt:ForcePersistence>true</trt:ForcePersistence></trt:SetVideoEncoderConfiguration>")


def _txt(el, path, default="-"):
    x = el.find(path, NS)
    return x.text if x is not None and x.text is not None else default


def describe(cfg: ET.Element) -> str:
    enc = _txt(cfg, "tt:Encoding")
    gov = _txt(cfg, f"tt:{enc}/tt:GovLength") if enc in ("H264", "H265") else "-"
    return (f"token={cfg.get('token')}  {enc}  {_txt(cfg, 'tt:Resolution/tt:Width')}x{_txt(cfg, 'tt:Resolution/tt:Height')}  "
            f"fps={_txt(cfg, 'tt:RateControl/tt:FrameRateLimit')}  bitrate={_txt(cfg, 'tt:RateControl/tt:BitrateLimit')}kbps  "
            f"GOV={gov}  quality={_txt(cfg, 'tt:Quality')}")


def creds_from_mediamtx(api: str, path: str):
    src = json.load(urllib.request.urlopen(f"{api.rstrip('/')}/v3/config/paths/get/{path}", timeout=5))["source"]
    u = urllib.parse.urlparse(src)
    return u.hostname, urllib.parse.unquote(u.username or ""), urllib.parse.unquote(u.password or "")


def build_new_config(cfg: ET.Element, encoding: str, width: int, height: int, fps: int, bitrate: int,
                     gov: int, profile: str) -> ET.Element:
    """Giữ nguyên các trường khác (Name, UseCount, Quality, Multicast, SessionTimeout), chỉ đổi codec/độ phân giải/tốc độ."""
    new = ET.fromstring(ET.tostring(cfg))
    new.find("tt:Encoding", NS).text = encoding
    new.find("tt:Resolution/tt:Width", NS).text = str(width)
    new.find("tt:Resolution/tt:Height", NS).text = str(height)
    rc = new.find("tt:RateControl", NS)
    rc.find("tt:FrameRateLimit", NS).text = str(fps)
    rc.find("tt:BitrateLimit", NS).text = str(bitrate)
    for codec in ("MPEG4", "H264", "H265"):
        old = new.find(f"tt:{codec}", NS)
        if old is not None:
            new.remove(old)
    if encoding in ("H264", "H265"):
        blk = ET.Element(f"{TT}{encoding}")
        ET.SubElement(blk, f"{TT}GovLength").text = str(gov)
        ET.SubElement(blk, f"{TT}{encoding}Profile").text = profile
        new.insert(list(new).index(rc) + 1, blk)  # thứ tự theo schema: ... RateControl, H264, Multicast ...
    return new


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("action", choices=["show", "set"])
    ap.add_argument("--host"); ap.add_argument("--user"); ap.add_argument("--password")
    ap.add_argument("--port", type=int, default=80)
    ap.add_argument("--mediamtx-api", default="http://localhost:9997")
    ap.add_argument("--mediamtx-path", default="cam-002", help="Path MediaMTX để lấy tài khoản camera (mặc định cam-002)")
    ap.add_argument("--token", default="VideoEncoder000", help="Encoder cần sửa (VideoEncoder000 = luồng chính)")
    ap.add_argument("--encoding", choices=["H264", "H265"])
    ap.add_argument("--width", type=int); ap.add_argument("--height", type=int)
    ap.add_argument("--fps", type=int); ap.add_argument("--bitrate", type=int, help="kbps")
    ap.add_argument("--gov", type=int, help="Khoảng cách keyframe (mặc định = 2 x fps)")
    ap.add_argument("--profile", default="Main", help="H264/H265 profile (Baseline/Main/High)")
    ap.add_argument("--dry-run", action="store_true", help="Chỉ in cấu hình sẽ gửi, không gửi")
    a = ap.parse_args()

    if a.host and a.user is not None and a.password is not None:
        host, user, pwd = a.host, a.user, a.password
    else:
        host, user, pwd = creds_from_mediamtx(a.mediamtx_api, a.mediamtx_path)
    cam = Onvif(host, user, pwd, a.port)
    cam.discover_media_path()
    print(f"Camera {host}: {cam.device_info()}")

    if a.action == "show":
        for c in cam.encoder_configs():
            print("  " + describe(c))
        opts = cam.encoder_options(a.token)
        res = opts.findall(".//tt:H264/tt:ResolutionsAvailable", NS)
        print(f"  Độ phân giải H264 hỗ trợ ({a.token}): " +
              ", ".join(f"{_txt(r, 'tt:Width')}x{_txt(r, 'tt:Height')}" for r in res))
        return 0

    missing = [n for n in ("encoding", "width", "height", "fps", "bitrate") if getattr(a, n) is None]
    if missing:
        ap.error("set cần đủ: " + ", ".join("--" + m for m in missing))
    gov = a.gov or max(1, a.fps * 2)

    if a.encoding == "H264":
        allowed = {(int(_txt(r, "tt:Width")), int(_txt(r, "tt:Height")))
                   for r in cam.encoder_options(a.token).findall(".//tt:H264/tt:ResolutionsAvailable", NS)}
        if allowed and (a.width, a.height) not in allowed:
            print(f"❌ Camera không hỗ trợ H264 {a.width}x{a.height}. Hỗ trợ: {sorted(allowed, reverse=True)}")
            return 2

    current = cam.encoder_config(a.token)
    print("Hiện tại: " + describe(current))
    new = build_new_config(current, a.encoding, a.width, a.height, a.fps, a.bitrate, gov, a.profile)
    print("Sẽ đổi : " + describe(new))
    if a.dry_run:
        print("\n(dry-run) Cấu hình XML sẽ gửi:\n" + ET.tostring(new, encoding="unicode"))
        return 0

    cam.set_encoder_config(new)
    print("✅ Camera đã nhận lệnh. Đọc lại để xác nhận:")
    print("  " + describe(cam.encoder_config(a.token)))
    print("Luồng RTSP sẽ khởi động lại vài giây; MediaMTX và AI tự kết nối lại.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
