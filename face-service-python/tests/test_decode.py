"""baselineFrame 解码的格式兼容（CR-013 全链路联调时暴露的问题）。

契约要纯 base64，但浏览器 canvas.toDataURL() 产出的是 data URL。
客户端已改为剥前缀；服务端再兜一层，避免解码层成为单点故障。
"""
import base64

import cv2
import numpy as np
import pytest

from app.engine import decode_b64_jpeg


def _jpeg_b64() -> str:
    ok, buf = cv2.imencode(".jpg", np.full((8, 8, 3), 128, np.uint8))
    assert ok
    return base64.b64encode(buf.tobytes()).decode()


def test_plain_base64():
    img = decode_b64_jpeg(_jpeg_b64())
    assert img.shape == (8, 8, 3)


def test_canvas_data_url_tolerated():
    img = decode_b64_jpeg("data:image/jpeg;base64," + _jpeg_b64())
    assert img.shape == (8, 8, 3)


def test_garbage_rejected_as_value_error():
    with pytest.raises(ValueError):
        decode_b64_jpeg("this is not base64 !!!")
