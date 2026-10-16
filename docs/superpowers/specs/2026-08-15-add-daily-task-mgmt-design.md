# Design: add-daily-task-mgmt

> 2026-08-15 · Superpowers brainstorming design
> OpenSpec change: `add-daily-task-mgmt`（proposal 已 done）
> 技术栈：Java (Gradle) + Spring Boot + PostgreSQL (Flyway) + Vue3 (Vite + Element Plus) + Docker Compose

## 设计目标

为 family-app 建立第一个可运行的垂直切片——"每日任务 CRUD"，同时一次性铺好完整的工程范式（标准三层架构、统一 API envelope、软删除、状态流转 DFA、DTO、全局异常处理、package-by-feature 目录结构、Element Plus 组件库、docker-compose 3 服务编排），使后续业务模块（家庭人员、采买清单、孩子日常）可以直接 copy-paste 模式零返工。

Approach 选择：**Approach 1 - Step-by-step 完整基底**（详见 §Approach 选择）。

---

## 1. 架构总览

### 1.1 Docker 拓扑

```
docker compose up -d
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│   ┌─────────────────┐           ┌─────────────────┐                    │
│   │  frontend       │   HTTP    │  backend        │   JDBC             │
│   │  (Vue3 + Vite)  │──────────▶│  (Spring Boot)  │─────────────┐      │
│   │   :5173 dev svr │  axios    │   :8080          │             │      │
│   └─────────────────┘           └────────┬────────┘             ▼      │
│                                          │              ┌────────────┐  │
│                                          │              │ PostgreSQL │  │
│                                          │              │   :5432    │  │
│                                          │              └────────────┘  │
│                                          │   Flyway auto-migrate on boot│
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

Python AI 模块占位：docker-compose.yml 中以注释 + `profiles: ["ai"]` 形式预留 `python-ai` service，默认不启动。

### 1.2 目录结构（monorepo 顶层并列）

```
family-app/
├── backend/                         Java Gradle 根（Groovy build.gradle）
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradlew / gradlew.bat
│   ├── gradle/wrapper/
│   ├── Dockerfile
│   └── src/
│       ├── main/
│       │   ├── java/com/familyapp/
│       │   │   ├── FamilyAppApplication.java
│       │   │   ├── common/               Result<T> / ResultCode / ApiException / GlobalExceptionHandler
│       │   │   ├── config/               Spring properties config (Flyway, Web, Actuator)
│       │   │   └── task/                 【package-by-feature】
│       │   │       ├── Task.java
│       │   │       ├── TaskStatus.java
│       │   │       ├── TaskRepository.java
│       │   │       ├── TaskService.java
│       │   │       ├── TaskServiceImpl.java
│       │   │       ├── TaskController.java
│       │   │       └── dto/
│       │   │           ├── CreateTaskRequest.java
│       │   │           ├── UpdateTaskRequest.java
│       │   │           └── TaskResponse.java
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/
│       │           ├── migration/
│       │           │   └── V1__Create_Tasks_Table.sql
│       │           └── rollback/          不作为 Flyway migration，SOP 验证手动执行
│       │               └── V2__Drop_Tasks_Table.sql
│       └── test/java/com/familyapp/      JUnit5 + Mockito + H2（集成测试不引 Testcontainers）
│           ├── task/
│           │   ├── TaskServiceTest.java      DFA 状态流转 + 软删除
│           │   ├── TaskRepositoryTest.java   findByIdIncludeDeleted
│           │   └── TaskControllerTest.java   MockMvc 5 端点 + 参数校验
│           └── FamilyAppApplicationTest.java Smoke: Context loads
│
├── frontend/                        Vue3 + Vite 根
│   ├── package.json
│   ├── vite.config.js               Vite + Element Plus 按需 import 插件 + /api proxy
│   ├── index.html
│   ├── Dockerfile
│   └── src/
│       ├── main.js                  Element Plus 按需注册
│       ├── App.vue
│       ├── api/tasks.js             axios client + envelope 剥壳 interceptor
│       └── views/
│           └── TaskView.vue         el-form + el-table + ElMessage
│
├── docker-compose.yml               3 services: postgres / backend / frontend
│
├── openspec/                        OpenSpec 根目录（不改动）
├── .harness/                        Harness 根目录（不改动）
├── .handoffs/                       Handoffs 目录（不改动）
└── .gitignore / CLAUDE.md           已提交
```

### 1.3 端口分配

| 服务 | 端口（主机:容器） | 说明 |
|---|---|---|
| frontend (Vite dev) | `5173:5173` | 浏览器直接访问 |
| backend (Spring Boot) | `8080:8080` | API；actuator/health 暴露用于 docker healthcheck |
| postgres | `5432:5432` | 主机如需用 psql 直接连，也暴露出来 |

Vite dev proxy：`/api/*` → `http://backend:8080`，所以前端 axios 调 `/api/tasks` 会被 Vite 代理到后端 hostname `backend`（docker compose 内网 DNS）。

---

## 2. 数据库设计

### 2.1 `tasks` 表 DDL（`V1__Create_Tasks_Table.sql`）

```sql
CREATE TABLE IF NOT EXISTS tasks (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(200)   NOT NULL,
    description  TEXT,
    due_date     DATE,
    status       VARCHAR(20)    NOT NULL DEFAULT 'TODO'
        CONSTRAINT chk_tasks_status
        CHECK (status IN ('TODO','IN_PROGRESS','DONE','CANCELLED')),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_tasks_status_due_date
    ON tasks (status, due_date);
```

**不建 DB trigger**：`updated_at` 由 JPA `@PreUpdate` 维护（`Task` 实体上的 `@EntityListeners(AuditingEntityListener.class)` 或手动 `@PreUpdate` 方法）。

### 2.2 JPA 映射 + 软删除

```java
@Entity
@Table(name = "tasks")
@Where(clause = "deleted_at IS NULL")
public class Task {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.TODO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

`@Where` 让所有通过 Repository 的查询自动过滤 `deleted_at IS NULL`。Repository 暴露后门：

```java
public interface TaskRepository extends JpaRepository<Task, Long> {

    @Query("SELECT t FROM Task t WHERE t.id = :id")
    Optional<Task> findByIdIncludeDeleted(@Param("id") Long id);
}
```

`findByIdIncludeDeleted` 绕过 `@Where` 注解，只用于审计场景，**Service 层 delete 方法不暴露给 Controller**。

### 2.3 回滚脚本（`db/rollback/V2__Drop_Tasks_Table.sql`）

```sql
DROP INDEX IF EXISTS idx_tasks_status_due_date;
DROP TABLE IF EXISTS tasks;
```

SOP 阶段6 验证"回滚"时手动执行一次（不是 Flyway migration）。

### 2.4 状态流转 DFA

```
 ┌──────────┐  ┌──────────────┐  ┌──────┐
 │   TODO   │─▶│ IN_PROGRESS  │─▶│ DONE │
 └──────────┘  └──────┬───────┘  └──────┘
            │         │             │
            └─────────┴─────────────┴────┐
                                        ▼
                                  ┌────────────┐
                                  │ CANCELLED  │
                                  └────────────┘
```

合法路径（Service 层 `validateTransition(current, target)` 方法校验）：

| Current ＼ Target | TODO | IN_PROGRESS | DONE | CANCELLED |
|---|---|---|---|---|
| **TODO** | ✅（不变化） | ✅ | ✅（可直接做完） | ✅ |
| **IN_PROGRESS** | ✅（可回退） | ✅ | ✅ | ✅ |
| **DONE** | ❌ | ❌ | ✅ | ❌ |
| **CANCELLED** | ❌ | ❌ | ❌ | ✅ |

非法 → 抛 `ApiException(BAD_REQUEST_STATUS_TRANSITION)` → GlobalExceptionHandler 包成 envelope `{code: 40011, msg: "invalid status transition", data: null}`，HTTP 400。

---

## 3. 后端 API 设计

### 3.1 全局 envelope

```json
{ "code": 0, "msg": "ok", "data": ... }
```

| 含义 | code | HTTP status |
|---|---|---|
| 成功 | 0 | 200（POST create 返回 201） |
| 标题空 | 40001 | 400 |
| 标题超 200 | 40002 | 400 |
| due_date 格式非法 | 40003 | 400 |
| status 流转非法 | 40011 | 400 |
| task 不存在 | 40401 | 404 |
| 兜底异常 | 50000 | 500 |

`ResultCode.java` 枚举承载 code 值。`Result<T>` 泛型类包装 data。

### 3.2 5 个端点

| Method | Path | 入参 | 成功返回 (HTTP / data) | 失败 code |
|---|---|---|---|---|
| `POST` | `/api/tasks` | `CreateTaskRequest { title*, description?, dueDate?, status? }` （Bean Validation） | 201 / `TaskResponse` | 40001 / 40002 / 40003 |
| `GET` | `/api/tasks` | —— | 200 / `TaskResponse[]` | —— |
| `GET` | `/api/tasks/{id}` | Path id（Long） | 200 / `TaskResponse` | 40401 |
| `PUT` | `/api/tasks/{id}` | Path id + `UpdateTaskRequest { title?, description?, dueDate?, status? }` | 200 / `TaskResponse` | 40002 / 40003 / 40011 / 40401 |
| `DELETE` | `/api/tasks/{id}` | Path id（软删除，不真删） | 200 / `true` | 40401 |

### 3.3 DTO 字段

- **CreateTaskRequest**：`@NotBlank @Size(max=200) String title`；`String description`；`@PastOrPresent LocalDate dueDate`（可空）；`TaskStatus status`（可空，默认 TODO）
- **UpdateTaskRequest**：所有字段可空（部分更新语义）。`@Size(max=200) String title`；`LocalDate dueDate`；`TaskStatus status`
- **TaskResponse**：`Long id`，`String title`，`String description`，`LocalDate dueDate`，`TaskStatus status`，`Instant createdAt`，`Instant updatedAt`（**不含** deletedAt）

### 3.4 三层职责

| 层 | 职责 | 不做的事 |
|---|---|---|
| TaskController | 参数校验（`@Valid @RequestBody`）、调 Service、把 Service 返回的 Entity/DTO 包成 `Result<T>` | 不写业务逻辑，不 catch 业务异常 |
| TaskService | DFA 状态流转校验、软删除（`setDeletedAt(now)` 后 save 非 `delete`）、查不到抛 `ApiException(TASK_NOT_FOUND)`、DTO ↔ Entity 转换 | 不写 HTTP status、不写 envelope |
| TaskRepository | JpaRepository + `findByIdIncludeDeleted` 绕过 `@Where` | 没有业务逻辑 |

### 3.5 全局异常

`GlobalExceptionHandler extends ResponseEntityExceptionHandler`：
- 捕获 `ApiException` → Result + 对应 HTTP status
- 捕获 `MethodArgumentNotValidException`（Bean Validation 失败）→ `code=40001` + 第一条 validation message
- 捕获兜底 `Exception` → `code=50000, msg="internal error"`（永远不暴露堆栈）

---

## 4. 前端设计（Vue3 + Vite + Element Plus）

### 4.1 依赖

```json
{
  "dependencies": {
    "vue": "^3.4",
    "axios": "^1.7",
    "element-plus": "^2.7"
  },
  "devDependencies": {
    "vite": "^5.3",
    "@vitejs/plugin-vue": "^5.0",
    "unplugin-auto-import": "^0.18",
    "unplugin-vue-components": "^0.27"
  }
}
```

明确不引（下个 change 加）：vue-router、pinia、eslint、prettier、vitest、@vue/test-utils。

### 4.2 Vite 配置要点（vite.config.js）

```js
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] })
  ],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://backend:8080',
        changeOrigin: true
      }
    }
  }
})
```

### 4.3 axios 封装（src/api/tasks.js）

```js
const client = axios.create({ baseURL: '/api', timeout: 10_000 })

client.interceptors.response.use(
  (resp) => {
    const { code, msg, data } = resp.data
    if (code !== 0) {
      ElMessage.error(msg || 'request failed')
      throw new Error(msg || 'request failed')
    }
    return data
  },
  (err) => {
    ElMessage.error(err.message || 'network error')
    throw err
  }
)

export const tasksApi = {
  list:   ()             => client.get('/tasks'),
  create: (payload)      => client.post('/tasks', payload),
  get:    (id)           => client.get(`/tasks/${id}`),
  update: (id, payload)  => client.put(`/tasks/${id}`, payload),
  del:    (id)           => client.delete(`/tasks/${id}`),
}
```

### 4.4 TaskView.vue 功能

页面结构：
```
┌─ 顶部 el-form（创建任务）──────────────────────────────────────┐
│  el-input(title)  el-input(description)  el-date-picker(due)    │
│  el-button(submit)                                                │
├─ el-table（任务列表）───────────────────────────────────────────┤
│  id | title | status | due_date | 操作(el-button 完成/删除)      │
│  ...                                                              │
└─────────────────────────────────────────────────────────────────┘
```

交互：
1. `onMounted` → `tasksApi.list()` → 渲染表格
2. 提交表单 → `tasksApi.create({title, description, dueDate})` → `ElMessage.success` → 重新 list
3. "完成"按钮 → `tasksApi.update(id, { status: 'DONE' })` → `ElMessage.success` → 重新 list
4. "删除"按钮 → `ElMessageBox.confirm` 二次确认 → `tasksApi.del(id)` → `ElMessage.success` → 重新 list

错误统一靠 axios interceptor 里的 `ElMessage.error`，组件内不重复。

---

## 5. 测试策略

### 5.1 Java（JUnit5 + Mockito + H2 in-memory）

`./gradlew test` 必须 0 failure。

| 测试类 | 覆盖点 |
|---|---|
| `FamilyAppApplicationTest` | `@SpringBootTest` smoke：context loads + Flyway 在 H2 上跑 migration 通过 |
| `TaskServiceTest` | Unit（Mockito mock repo）：所有 7 条合法 DFA 路径通过；2 条非法路径（DONE→TODO, CANCELLED→TODO）抛 ApiException(40011)；软删除后 list 只返回未删除（返回 Entity 而非从 repo 取，这里验证 Service 内部 state） |
| `TaskRepositoryTest` | `@DataJpaTest` H2：`findByIdIncludeDeleted` 正确返回软删除记录；`findById` 跳过软删除 |
| `TaskControllerTest` | `@SpringBootTest` + MockMvc：5 端点 HTTP 层（空标题返回 40001、不存在 id 返回 40401、非法 status 流转返回 40011、POST 成功返回 201 + envelope）；软删除一条后 `GET /api/tasks` 不包含它 |

**不用 Testcontainers**（首次 change 加速，H2 足够 CRUD 验证；Postgres 特有 CHECK 约束在 MockMvc 层不直接验证，靠 Service 层 + Flyway 配置已足够保证）。

### 5.2 Vue

`cd frontend && npm run build` 必须 0 error。
Lint（eslint + prettier）和单元测试（vitest）放**下个 change** 引入。

### 5.3 Docker

| 验证项 | 命令 | 结果 |
|---|---|---|
| Compose 文件合法 | `docker compose config` | exit 0，列出 postgres / backend / frontend 3 services |
| 启动无 crash loop | `docker compose up -d` 后 `docker compose ps` | 3 容器 Up / healthy |
| API 可达 | `curl -s http://localhost:8080/api/tasks`（或浏览器） | `{"code":0,"msg":"ok","data":[]}` |
| 前端页面可达 | `curl -s http://localhost:5173/` | 返回含 `<script type="module" src="/src/main.js">` 的 HTML |

### 5.4 DB 回滚

⚠️ 生产环境**永远不要用 `flyway clean`**，以下流程仅用于 SOP 开发验证。

Flyway Community Edition（CE）不支持 `flyway undo`；生产级回滚策略是"V2 增量反向 migration"，本次 change 的回滚是 SOP 验证用途，采用「脚本语法校验 + clean+reapply 恢复性」两步验证：

1. **正向 apply**：启动 backend，`V1__Create_Tasks_Table.sql` 成功 apply
   - `psql`：`\d tasks` 看到表结构；`SELECT version FROM flyway_schema_history` → `1`
2. **回滚脚本语法&语义校验（不破坏 schema_history）**：
   - 新建一个临时空库 `familyapp_rollback_test` 或用事务原子执行：`psql -U familyapp_user -d familyapp -c "BEGIN; \i backend/src/main/resources/db/rollback/V2__Drop_Tasks_Table.sql; \d tasks; ROLLBACK;"`
   - 预期：BEGIN 内 `\d tasks` 报"relation does not exist"，ROLLBACK 后回到原状态；整个脚本无 SQL 语法错误 → 回滚脚本正确性验证通过
3. **恢复性验证**：
   - `./gradlew flywayClean`（Gradle Flyway 插件，通过 docker 暴露的 5432 连真实 Postgres）—— 清 schema + 清 flyway_schema_history
   - `./gradlew flywayMigrate` → 重新 apply V1
   - `psql`：`\d tasks` 表存在 + `flyway_schema_history.version` 回到 `1` → **可从干净状态恢复验证通过**
4. 最终 backend 启动时 `SPRING_FLYWAY_ENABLED=true` 自动 apply（schema_history 已经有 V1 记录，Flyway 跳过），任务正常工作。

### 5.5 OpenSpec

`openspec validate` 退出 0；`openspec status --change add-daily-task-mgmt --json` 的 `applyRequires` 全满足（tasks artifact done）。

---

## 6. 错误处理、安全、边界

**错误处理**：
- Java GlobalExceptionHandler 兜底所有异常，`msg` 不暴露堆栈，production profile 不配置 `server.error.include-stacktrace=always`。
- 前端 axios interceptor 两个分支（code≠0 / 网络异常）都走 ElMessage 提示，避免白屏。

**安全**：
- SQL 注入：所有查询走 JPA Repository 方法或 `@Query` 带 `:param`，不做字符串拼接。
- XSS：Vue 模板输出默认转义；不使用 `v-html`。
- CORS：靠 Vite dev proxy。Spring Boot 层暂时不开放 `@CrossOrigin`（生产场景独立 change）。
- 软删除防绕过：Service 层 `findByIdIncludeDeleted` 不暴露给 Controller。

**边界 / 性能**：
- `GET /api/tasks` 不加分页（家庭级数据量），下个 change 加分页和过滤。
- title 长度 200、description TEXT、due_date DATE——都经 Bean Validation + DB 列类型双重校验。
- status 列 CHECK constraint 是 Postgres 层最后防线，防止非法字符串绕过 JPA 枚举直接落库。

**Docker 编排细节**：
- postgres: `postgres:16-alpine`，volume `.volumes/postgres`，healthcheck `pg_isready`，env `POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD = familyapp / familyapp_user / placeholder-postgres-password`
- backend:
  - `build ./backend`，`depends_on: { postgres: { condition: service_healthy } }`
  - env：`SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/familyapp`、`SPRING_FLYWAY_ENABLED=true`、`SPRING_JPA_HIBERNATE_DDL_AUTO=validate`（仅校验，不由 Hibernate 自动建表）
  - healthcheck：`curl -sf http://localhost:8080/actuator/health || exit 1`（需引入 `spring-boot-starter-actuator` 依赖）
  - `backend/Dockerfile`：**多阶段构建**
    - 阶段 1（build）：`FROM eclipse-temurin:17-jdk-jammy AS builder` → `WORKDIR /app` → `COPY gradle* .` → `COPY src ./src` → `RUN ./gradlew bootJar -x test`（构建 jar，跳过已由 SOP 单独跑过的 test 避免二次跑）
    - 阶段 2（runtime）：`FROM eclipse-temurin:17-jre-jammy` → `WORKDIR /app` → `COPY --from=builder /app/build/libs/*.jar app.jar` → `EXPOSE 8080` → `ENTRYPOINT ["java","-jar","app.jar"]`
  - 主机上首次 `./gradlew test` 单独跑，不依赖 docker build（避免每次 build 都跑 test）
- frontend:
  - `build ./frontend`，开发环境跑 `npm run dev`（Vite 热更新），`depends_on: { backend: { condition: service_healthy } }`，ports `5173:5173`
  - `frontend/Dockerfile`：`FROM node:20-alpine` → `WORKDIR /app` → `COPY package*.json ./` → `RUN npm install` → `COPY . .` → `EXPOSE 5173` → `CMD ["npm","run","dev","--","--host","0.0.0.0"]`
  - 重要：docker-compose 中 **bind mount** `./frontend:/app` + 匿名 volume `/app/node_modules`，这样 Vite HMR 生效、源码修改即时反映，避免每次代码改动都要 `docker compose build frontend`
- **`.gitignore` 补充**：`backend/build/`、`frontend/dist/`、`.volumes/`（docker data volume）—— 这 3 个 pattern 需要加入 `.gitignore`

**Python AI 占位**：
```yaml
#  python-ai:
#    build: ./ai
#    profiles: ["ai"]
#    depends_on:
#      backend: { condition: service_healthy }
```
注释中，后续 change 取消注释并补 `./ai/` 目录（pyproject.toml + ruff/mypy + pytest + Dockerfile + FastAPI 骨架）。

---

## 附录 A · Approach 选择（决策记录）

| 方案 | 描述 | 取舍 |
|---|---|---|
| 🅰️ Approach 1 **Step-by-step 完整基底（选用）** | 所有选型按"完整范式"：三层架构 / 统一 envelope / 软删除 / 4 值 status DFA / Flyway / H2 + 完整测试 / Element Plus / Docker 3 服务 | 首次 change 代码量大 ~25 文件，下次 change 零返工 |
| 🅱️ Approach 2 最小可跑版 + 重构债务 | 保留三层 + Flyway，其余（软删除 / envelope / 4 值）压最小，明确重构债务清单下次还 | 跑得最快，但用户选择倾向完整方案，立即重构反而浪费 |
| 🅲 Approach 3 骨架先行，业务后置 | 这次 change 只做 DB/docker/工程骨架，业务 CRUD 下个 change | 粒度更细但首次 change 无业务成就感，对用户体验差 |

最终选 🅰️，因用户在 5 个澄清问题中 3 次选"完整方案"（4 值 status / 软删除 / 统一 envelope）。

## 附录 B · 设计中已修正的澄清偏差

| # | 原方案 | 修正后 | 修正原因 |
|---|---|---|---|
| 1 | build.gradle **kts（Kotlin DSL）** | build.gradle **groovy** | 用户明确选择 Groovy |
| 2 | updated_at 用 PostgreSQL BEFORE UPDATE trigger | 用 JPA `@PreUpdate` | 用户选择"应用层负责，跨库移植好" |
| 3 | 前端不引任何 UI 库 | **引入 Element Plus** + 按需 import 插件 | 用户明确要求"加 UI 组件库" |

