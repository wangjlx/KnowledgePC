# KnowledgePC

> 🌐 **在线主页**：https://wangjlx.github.io/KnowledgePC/

本地优先的个人知识管理平台。单机部署、局域网访问，支持 Markdown 知识条目、工作记录、双链笔记、知识图谱、标签树、KB 目录增量导入与附件管理。

## 功能特性

- **知识条目**：Markdown 编辑、大纲导航、版本历史、收藏、分享（公开链接）
- **工作记录**：日报/会议/想法等类型，独立于知识条目
- **双链笔记**：`[[页面名]]` 与 `[[目录/页面名]]` 语法，导入时自动建立双向链接
- **知识图谱**：Canvas 力导向图，节点拖拽布局记忆（±5000 世界坐标护栏）
- **标签系统**：层级标签树、颜色管理、批量打标
- **KB 目录同步**：监控指定目录（wiki / external-wiki / team-wiki / skills 等），按文件 mtime 增量导入；`?relinks=1` 可全量重建双链
- **用户与权限**：admin/user 角色，会话 24 小时过期
- **数据导出/导入**：JSON 全量备份，含图谱布局坐标

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17（JDK 内置 HttpServer 风格 socket 实现，无框架） |
| 数据库 | SQLite（WAL 模式，xerial jdbc-driver） |
| 前端 | 原生 HTML/CSS/JavaScript，无构建步骤 |
| 构建 | Gradle Wrapper 或 scripts（见下） |

## 快速开始

### 环境要求

- JDK 17+
- （可选）Git

### 构建与运行

```bash
# 方式一：Gradle
./gradlew :server:installDist
./server/build/install/server/bin/server

# 方式二：脚本编译（Windows）
compile.bat
start.bat
```

服务默认监听 `http://127.0.0.1:8080`。

### 环境变量

| 变量 | 说明 | 默认值 |
|---|---|---|
| `KNOWLEDGE_KB_ROOT` | KB 知识库根目录（导入源），**必须为绝对路径或相对于工作目录** | `./KB` |
| `KNOWLEDGE_SEED_ADMIN_PASSWORD` | 首次初始化时 admin 账号的种子密码（≥8 位，否则忽略并生成随机密码打印到控制台一次） | 随机生成 |

> 安全提示：首次启动后请立即修改 admin 密码。

### 数据存储

- 数据库：`data/knowledge.db`（SQLite，自动建表）
- 附件：`data/attachments/<source_type>/<source_id>/<filename>`
- 日志：`server.log`

## 目录结构

```
KnowledgePC/
├── server/src/main/java/com/knowledge/   # Java 源码
│   ├── KnowledgeServer.java              # 入口
│   ├── ApiServer.java                    # HTTP 路由与业务逻辑
│   └── DatabaseHelper.java               # SQLite 初始化与工具
├── web/                                   # 前端（静态文件，直接被服务托管）
│   ├── index.html
│   ├── share.html                        # 分享页
│   ├── js/api.js                         # API 封装
│   └── js/app.js                         # 主应用逻辑
├── docs/                                  # 设计文档与更新记录
├── compile.bat / compile.sh              # 编译脚本
├── start.bat   / start.sh                # 启动脚本
└── stop.bat                              # 停止脚本
```

## API 概览

所有接口前缀 `/api`，认证方式：`Authorization: Bearer <token>`（登录接口返回 token）。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/login` `/auth/register` `/auth/logout` | 认证 |
| GET/POST/PUT/DELETE | `/entries[/:id]` | 知识条目 CRUD |
| GET | `/entries/:id/backlinks` `/links` `/versions` | 双链与版本 |
| CRUD | `/records[/:id]` | 工作记录 |
| CRUD | `/tags[/:id]` | 标签 |
| GET/POST | `/graph` `/graph/layout` | 图谱与布局 |
| GET | `/search?q=` `/stats` | 搜索与统计 |
| GET/POST | `/export` `/import` `/import/kb?relinks=0\|1` | 备份与 KB 导入 |
| POST | `/attachments/upload` | 附件上传（base64） |
| GET/POST/PUT/DELETE | `/admin/users[/:id]` | 用户管理（仅 admin） |

完整设计文档见 [docs/功能设计文档.md](docs/功能设计文档.md)，数据库结构见 [docs/数据库字典.md](docs/数据库字典.md)。

## 安全说明

- 密码使用 **PBKDF2-HmacSHA256**（120k 迭代 + 随机盐）存储；旧版无盐 SHA-256 哈希在首次登录时自动透明升级
- 会话 Token 存储在内存中，24 小时无操作过期；重启即失效
- 附件上传对文件名做路径剥离与扩展名黑名单过滤（拒绝 .exe/.bat/.js/.html/.svg 等）
- 静态文件服务做了规范化路径包含校验（防目录穿越），且不对外提供 `.bak/.log/.db/.md` 等敏感类型
- 前端启用 CSP（`default-src 'self'`，禁外链脚本与 iframe 嵌套）

已知限制：
- 单进程单连接串行处理请求（SQLite 场景下的正确性优先取舍），不适合高并发生产部署
- Token 保存在浏览器 localStorage，XSS 防御依赖 CSP 与输出转义，建议仅在可信局域网环境使用
- 分享页为公开访问（持有分享 token 即可读），请勿分享敏感条目

## 许可证

[MIT](LICENSE)
