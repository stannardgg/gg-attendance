"""让 pytest 从仓库根运行时也能导入 app 包。

CI 的 python job 是 `pytest -q face-service-python/tests`（在仓库根执行），
此时 face-service-python 不在 sys.path 上，`import app` 会失败。
本文件由 pytest 在收集阶段自动加载，把包根补进 sys.path。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
