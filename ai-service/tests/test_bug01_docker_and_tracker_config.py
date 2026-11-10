import os
from unittest.mock import patch
from src.core import human_detector

def test_dockerfile_copies_config_directory():
    dockerfile_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "Dockerfile"
    )
    assert os.path.exists(dockerfile_path), "Dockerfile not found"
    with open(dockerfile_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "COPY config/ ./config/" in content or "COPY config ./config" in content, (
        "Dockerfile must copy config/ directory into container to provide bytetrack config"
    )

def test_tracker_fallback_when_config_file_missing():
    # If the custom config file does not exist, detector must fallback to bytetrack.yaml
    with patch("os.path.exists", return_value=False):
        tracker_to_use = human_detector.get_tracker_config_path()
        assert tracker_to_use == "bytetrack.yaml"

def test_tracker_uses_custom_config_when_present():
    with patch("os.path.exists", return_value=True):
        tracker_to_use = human_detector.get_tracker_config_path()
        assert tracker_to_use == human_detector._BYTETRACK_CONFIG_PATH
