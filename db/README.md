# db · 数据库迁移

## 目录

```
db/
├─ migration/           Flyway 版本化迁移，只增不改
│  └─ V1__schema.sql    全库结构（15 张表）
└─ seed/                演示数据，生产不执行
   └─ seed.sql          1 ADMIN + 1 教师 + 1 班 + 3 学生 + 1 课程 + 33 条配置
```

## 命名与改写规则

| 规则 | 说明 |
|---|---|
| 文件名 | `V{序号}__{snake_case_描述}.sql`，序号连续不留空 |
| 已发布的迁移 | **只读**。改字段一律新增 `V{n+1}__alter_xxx.sql`，不许回头改 V1 |
| 可重复脚本 | 用 `R__` 前缀（如视图、字典刷新），每次校验和变化就重跑 |
| 一个迁移一件事 | 建表和灌数据不要混在同一条 |

> 为什么强调"已发布的只读"：`contract-v1` tag 之后，V1 的校验和是所有环境的基准。
> 有人本地改一个字，CI 与生产就会报 `Migration checksum mismatch`，排查成本极高。

## 本地跑迁移

**方式一：docker compose（B6 之后的标准姿势）**

```bash
docker compose up -d mysql
docker compose run --rm flyway migrate
```

**方式二：Flyway CLI**

```bash
flyway -url="jdbc:mysql://127.0.0.1:3306/face_attendance?allowPublicKeyRetrieval=true&useSSL=false" \
       -user=root -password="$MYSQL_ROOT_PASSWORD" \
       -locations=filesystem:db/migration \
       migrate
```

**方式三：拿到没有 Flyway 的机器上做结构验证**

只验证 DDL 能否在空库跑通时，可以直接用 mysql 客户端——**注意这不写 `flyway_schema_history`**，
所以只用于结构校验，不能当作正式迁移：

```bash
mysql -h127.0.0.1 -uroot -p -e "CREATE DATABASE face_gate DEFAULT CHARSET utf8mb4;"
mysql -h127.0.0.1 -uroot -p face_gate < db/migration/V1__schema.sql
MYSQL_DATABASE=face_gate ./contracts/scripts/check-contract.sh
```

## 演示数据

```bash
mysql -h127.0.0.1 -uroot -p face_attendance < db/seed/seed.sql
```

`seed.sql` 是幂等的（固定主键 + `INSERT IGNORE`），重复执行不会产生重复行。
演示账号密码写在文件头部注释里。

## 三条不可动摇的设计约定

1. **不建物理外键**。理由写在 `V1__schema.sql` 的文件头：写热点加锁、跨模块耦合、归档流程被挡。
   关联完整性靠索引 + 事务内校验 + 契约断言脚本。
2. **单管理员靠生成列**。`account.admin_singleton` + `uk_single_admin`，
   不用触发器（批量导入时会被绕过）。
3. **班级与课程零耦合**。`course` 没有 `class_id`，`class_group` 没有 `course_id`，
   全库不存在 `course_student`。这条由 `contracts/scripts/check-contract.sh` 守着。
