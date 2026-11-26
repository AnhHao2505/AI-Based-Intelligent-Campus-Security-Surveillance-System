"""Isolated PyAV fMP4 encoder worker process.

Runs in a dedicated subprocess (e.g. on macOS) without importing cv2 so that
PyAV's bundled libavdevice dylib never coexists with OpenCV's bundled
libavdevice dylib in the same Objective-C runtime process.
"""
import os
import sys

import av
import numpy as np


def main() -> int:
    if len(sys.argv) < 6:
        sys.stderr.write("Usage: _av_fmp4_worker.py <file_path> <fps> <width> <height> <gop>\n")
        return 2

    file_path = sys.argv[1]
    fps = max(1, int(round(float(sys.argv[2]))))
    width = int(sys.argv[3])
    height = int(sys.argv[4])
    gop = int(sys.argv[5])
    frame_bytes = width * height * 3

    os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)
    raw_file = None
    container = None
    stream = None
    try:
        raw_file = open(file_path, "wb")
        container = av.open(
            raw_file,
            mode="w",
            format="mp4",
            options={
                "movflags": "frag_keyframe+empty_moov+default_base_moof",
                "flush_packets": "1",
            },
        )
        stream = container.add_stream("libx264", rate=fps)
        stream.width = width
        stream.height = height
        stream.pix_fmt = "yuv420p"
        stream.options = {
            "preset": "ultrafast",
            "tune": "zerolatency",
            "g": str(gop),
        }

        stdin_buf = sys.stdin.buffer
        while True:
            chunk = stdin_buf.read(frame_bytes)
            if not chunk or len(chunk) < frame_bytes:
                break
            arr = np.frombuffer(chunk, dtype=np.uint8).reshape((height, width, 3))
            av_frame = av.VideoFrame.from_ndarray(arr, format="bgr24")
            for packet in stream.encode(av_frame):
                container.mux(packet)
            raw_file.flush()

        for packet in stream.encode():
            container.mux(packet)
        raw_file.flush()
        return 0
    finally:
        if container is not None:
            try:
                container.close()
            except Exception:
                pass
        if raw_file is not None:
            try:
                raw_file.close()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
