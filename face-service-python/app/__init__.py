"""face-service · 人脸内核服务（Python）。

职责边界（项目文档 §14.3 WP-05/06/07）：
  只做人脸相关的计算与判定，**不碰业务库**——学生的注册、会话的归属
  都通过 Java 的内部接口查询；这里甚至不直接连 MySQL。

模块划分：
  engine/    四个模型的封装（检测 / 识别 / 关键点 / 活体），纯计算，无状态
  index/     向量库：全量加载 + 增量刷新 + 1:N 搜索
  challenge/ 挑战状态机：Redis 会话、动作判定、三道门禁编排
  api/       FastAPI 路由，对齐 contracts/openapi/face-api.yaml
"""

__version__ = "0.1.0"
