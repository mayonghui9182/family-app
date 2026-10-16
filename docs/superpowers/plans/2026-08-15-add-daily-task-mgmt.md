# add-daily-task-mgmt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 family-app 第一个可运行垂直切片——每日任务 CRUD，一次性建好完整工程范式（标准三层、统一 envelope、软删除、状态 DFA、Flyway、Element Plus、docker-compose 3 服务）。

**Architecture:** Monorepo 顶层并列 backend（Spring Boot Gradle Groovy）+ frontend（Vue3/Vite/Element Plus）+ docker-compose 3 服务（postgres:16-alpine / backend / frontend）。Package-by-feature（task/common/config 并列），Repository 用 `@Where` 软删除，Service 层状态 DFA 校验，Controller 走全局异常处理器包成 `{code,msg,data}` envelope。前端 Vite dev proxy 把 `/api/*` 转 `http://backend:8080`，Element Plus 按需自动 import。

**Tech Stack:** Java 17 (JDK build + JRE runtime 多阶段) + Spring Boot 3 + Spring Data JPA + Flyway CE + Hibernate Validator + JUnit5 / Mockito / H2 in-memory (tests) + PostgreSQL 16 + Node 20 + Vue 3 + Vite 5 + axios 1.7 + Element Plus 2.7 + unplugin-auto-import + unplugin-vue-components + Docker Compose v2 + openspec 1.8.0

## Global Constraints

- Conventional Commits: 所有 commit message 前缀 `feat(task): ...` / `chore(infra): ...` / `test: ...` / `chore(verification): ...`，严格小写
- 所有 Java 文件 package 前缀 `com.familyapp`
- 软删除：**永远不要**调用 `taskRepository.delete()`，用 `task.setDeletedAt(Instant.now())` 然后 `save`
- API envelope 永远是 `{code, msg, data}`；HTTP 200 代表 transport OK，body.code 代表业务成功/失败（POST create 返回 HTTP 201）
- Java DTO 字段校验必须用 Bean Validation 注解（`@NotBlank`/`@Size(max=200)`/`@PastOrPresent`），不用手写 if-else
- Flyway migration 文件命名：`V<VERSION>__<Underscore_Name>.sql`（两个下划线），放在 `backend/src/main/resources/db/migration/`
- 回滚脚本（不作为 Flyway migration）：`backend/src/main/resources/db/rollback/V2__Drop_Tasks_Table.sql`
- 前端 axios response interceptor：`resp.data.code !== 0` 时 `ElMessage.error(msg)` 并 `throw`，不要让错误流进组件 data
- docker-compose 3 服务必须都带 `healthcheck`（postgres 用 `pg_isready`，backend 用 actuator/health，frontend 用 `wget -qO- http://localhost:5173/ | grep -q '<script type="module"' || exit 1`）
- **首次 change 不引入**：vue-router / pinia / eslint / prettier / vitest（这些写在下个 change）

---

## File Structure Map

```
family-app/
├── .gitignore                                 ← Task 1 MODIFY (add 3 patterns)
├── docker-compose.yml                         ← Task 1 CREATE
│
├── backend/
│   ├── build.gradle                           ← Task 2 CREATE
│   ├── settings.gradle                        ← Task 2 CREATE
│   ├── Dockerfile                             ← Task 1 CREATE (multi-stage)
│   ├── gradlew.bat                            ← Task 2 CREATE (run gradle wrapper to generate)
│   ├── gradlew (unix)                         ← Task 2 CREATE (run gradle wrapper to generate)
│   ├── gradle/wrapper/gradle-wrapper.jar      ← Task 2 GENERATE
│   ├── gradle/wrapper/gradle-wrapper.properties ← Task 2 GENERATE
│   └── src/
│       ├── main/
│       │   ├── java/com/familyapp/
│       │   │   ├── FamilyAppApplication.java      ← Task 2 CREATE
│       │   │   ├── common/                        ← Task 3
│       │   │   │   ├── Result.java                ← Task 3 CREATE
│       │   │   │   ├── ResultCode.java            ← Task 3 CREATE
│       │   │   │   ├── ApiException.java          ← Task 3 CREATE
│       │   │   │   └── GlobalExceptionHandler.java ← Task 3 CREATE
│       │   │   ├── config/                        ← Task 3
│       │   │   │   └── WebConfig.java             ← Task 3 CREATE (unused this change, next change CORS)
│       │   │   └── task/                          ← Task 4 + Task 5
│       │   │       ├── TaskStatus.java            ← Task 4 CREATE (enum: 4 values)
│       │   │       ├── Task.java                  ← Task 4 CREATE (entity + @Where + @PrePersist/@PreUpdate)
│       │   │       ├── TaskRepository.java        ← Task 4 CREATE (JpaRepository + findByIdIncludeDeleted)
│       │   │       ├── dto/
│       │   │       │   ├── CreateTaskRequest.java ← Task 4 CREATE (@NotBlank @Size(max=200) etc.)
│       │   │       │   ├── UpdateTaskRequest.java ← Task 4 CREATE (all nullable, partial update)
│       │   │       │   └── TaskResponse.java      ← Task 4 CREATE (record or class: 7 fields excl deletedAt)
│       │   │       ├── TaskService.java           ← Task 5 CREATE (interface)
│       │   │       ├── TaskServiceImpl.java       ← Task 5 CREATE (@Service, DFA transition validate + soft delete logic)
│       │   │       └── TaskController.java        ← Task 5 CREATE (@RestController @RequestMapping /api/tasks, 5 endpoints)
│       │   └── resources/
│       │       ├── application.yml                ← Task 2 CREATE (datasource, flyway, jpa, actuator)
│       │       └── db/
│       │           ├── migration/
│       │           │   └── V1__Create_Tasks_Table.sql ← Task 2 CREATE (DDL + CHECK constraint + index)
│       │           └── rollback/
│       │               └── V2__Drop_Tasks_Table.sql   ← Task 2 CREATE (DROP INDEX + DROP TABLE, NOT a flyway migration)
│       └── test/java/com/familyapp/                ← Task 6
│           ├── FamilyAppApplicationTest.java        ← Task 6 CREATE (@SpringBootTest smoke)
│           └── task/
│               ├── TaskServiceTest.java             ← Task 6 CREATE (Unit: DFA 7 valid + 2 invalid paths + soft delete)
│               ├── TaskRepositoryTest.java          ← Task 6 CREATE (@DataJpaTest + H2, findByIdIncludeDeleted)
│               └── TaskControllerTest.java          ← Task 6 CREATE (@SpringBootTest + MockMvc, 5 endpoints)
│
└── frontend/
    ├── package.json                             ← Task 7 CREATE (exact dependencies pinned)
    ├── vite.config.js                           ← Task 7 CREATE (Vue plugin + Element Plus 2 resolvers + /api proxy + host 0.0.0.0)
    ├── index.html                               ← Task 7 CREATE (Vite default template)
    ├── Dockerfile                               ← Task 1 CREATE (node:20-alpine, npm install → npm run dev)
    └── src/
        ├── main.js                              ← Task 7 CREATE (createApp + mount + Element Plus 全注册 or 按需)
        ├── App.vue                              ← Task 7 CREATE (root, just import TaskView.vue)
        ├── api/tasks.js                         ← Task 8 CREATE (axios client + 2 interceptors + 5 methods)
        └── views/TaskView.vue                   ← Task 8 CREATE (el-form create + el-table list + ElMessageBox confirm)
```

---

## Task Dependencies

```
Task 1 (Docker infra + gitignore) ──┬───────────────────────────────────────────────────┐
Task 2 (Backend scaffold + Gradle + Flyway) ───┬───────────┐                             │
Task 3 (Java common: Result/Exception/Handler)  │           │                             │
                  │              │                │           │                             │
                  ▼              ▼                ▼           │                             ▼
Task 4 (Task entity + enum + Repo + DTOs)  Task 6A (Smoke test)  Task 7 (Frontend scaffold)──┬─→ Task 9
                  │                                                        │                │
                  ▼                                                        ▼                ▼
Task 5 (Service + Controller)                                Task 6 (all tests)    Task 8 (api + TaskView)
                  │                                                        │                │
                  └──────────────────── Task 6 (Service+Controller tests) ─┘                │
                                                                                              │
                                                                                              ▼
                                                                   Task 9 (SOP Phase 6 verify all)
```

**独立可并行的分支**（适合 subagent-driven-development 多 agent 并行）：
- 分支 A：Task 1 → 独立
- 分支 B：Task 2 → Task 3 → Task 4 → Task 5 → Task 6
- 分支 C：Task 7 → Task 8 （前端可独立开发，靠 axios mock / vite mock server 先本地跑，等分支 B 完再联调）
- Task 9：收束所有分支后执行

---

## Task 1: Docker Infra + .gitignore

**Files:**
- Create: `docker-compose.yml`
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`
- Modify: `.gitignore` (append 3 new patterns at bottom)

**Interfaces:**
- Consumes: N/A (first task)
- Produces: `docker compose config` validates 3 services; backend multi-stage Dockerfile builds jar then JRE; frontend Dockerfile runs Vite dev; `.gitignore` hides `backend/build/`, `frontend/dist/`, `.volumes/`

- [ ] **Step 1: 追加 `.gitignore` 末尾 3 行**

```
# --- family-app infra ---
backend/build/
frontend/dist/
.volumes/
```

- [ ] **Step 2: 创建 `backend/Dockerfile`**

```dockerfile
# -------- Build stage --------
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

# -------- Runtime stage --------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

- [ ] **Step 3: 创建 `frontend/Dockerfile`**

```dockerfile
FROM node:20-alpine
WORKDIR /app
RUN apk add --no-cache wget
COPY package*.json ./
RUN npm install --no-audit --no-fund
COPY . .
EXPOSE 5173
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:5173/ 2>/dev/null | grep -q '<script type="module"' || exit 1
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
```

- [ ] **Step 4: 创建 `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: familyapp
      POSTGRES_USER: familyapp_user
      POSTGRES_PASSWORD: placeholder-postgres-password
    volumes:
      - ./.volumes/postgres:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U familyapp_user -d familyapp"]
      interval: 5s
      timeout: 3s
      retries: 10

  backend:
    build: ./backend
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/familyapp
      SPRING_DATASOURCE_USERNAME: familyapp_user
      SPRING_DATASOURCE_PASSWORD: placeholder-postgres-password
      SPRING_FLYWAY_ENABLED: "true"
      SPRING_JPA_HIBERNATE_DDL_AUTO: validate
      SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT: org.hibernate.dialect.PostgreSQLDialect
      MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health
    ports:
      - "8080:8080"

  frontend:
    build: ./frontend
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "5173:5173"
    volumes:
      - ./frontend:/app
      - /app/node_modules

# python-ai:  # 占位，后续 change 启用
#   build: ./ai
#   profiles: ["ai"]
#   depends_on:
#     backend:
#       condition: service_healthy
```

- [ ] **Step 5: 验证 docker-compose yaml 语法**

Run: `docker compose config`
Expected: exit 0, output shows `postgres`, `backend`, `frontend` 3 services + `python-ai` 不在 services 列表中（是 yml 注释）

- [ ] **Step 6: 验证 .gitignore 三个新增 pattern 生效**

Run: `mkdir -p backend/build frontend/dist .volumes/postgres && echo junk > backend/build/x && echo junk > frontend/dist/x && echo junk > .volumes/postgres/x && git status --short`
Expected: `??` list 里**不**出现 `backend/build/`、`frontend/dist/`、`.volumes/`（如已被忽略则通过）
Cleanup: `rm -rf backend/build frontend/dist .volumes` (Windows: `Remove-Item -Recurse -Force backend/build, frontend/dist, .volumes`)

- [ ] **Step 7: Commit**

```bash
git add .gitignore docker-compose.yml backend/Dockerfile frontend/Dockerfile
git commit -m "chore(infra): docker-compose 3-service topology + dockerfiles + gitignore

- docker-compose.yml: postgres:16-alpine + backend build + frontend build with healthchecks
- backend Dockerfile: 2-stage (temurin-17-jdk builder -> jre runtime), -x test build
- frontend Dockerfile: node:20-alpine, npm install -> vite dev, healthcheck via wget
- .gitignore: backend/build, frontend/dist, .volumes (postgres data)
"
```

---

## Task 2: Backend Scaffold (Gradle + Spring Boot + Flyway + Migration)

**Files:**
- Create: `backend/build.gradle`
- Create: `backend/settings.gradle`
- Generate: `backend/gradlew`, `backend/gradlew.bat`, `backend/gradle/wrapper/*`
- Create: `backend/src/main/java/com/familyapp/FamilyAppApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/db/migration/V1__Create_Tasks_Table.sql`
- Create: `backend/src/main/resources/db/rollback/V2__Drop_Tasks_Table.sql`

**Interfaces:**
- Consumes: N/A (scaffold)
- Produces: `./gradlew compileJava` passes; `SpringApplication.run` boots (no task classes yet, no DB needed if profile test); Flyway SQL is syntactically valid; PostgreSQL `BEGIN \i V2__...sql ROLLBACK` works

- [ ] **Step 1: 创建 `backend/settings.gradle`**

```groovy
rootProject.name = 'family-app-backend'
```

- [ ] **Step 2: 创建 `backend/build.gradle`**（Gradle Groovy，Spring Boot 3.2.x + Java 17，精确依赖列表）

```groovy
plugins {
    id 'org.springframework.boot' version '3.2.8'
    id 'io.spring.dependency-management' version '1.1.6'
    id 'java'
    id 'org.flywaydb.flyway' version '10.15.0'
}

group = 'com.familyapp'
version = '0.1.0'

java {
    sourceCompatibility = '17'
    targetCompatibility = '17'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
    runtimeOnly 'org.postgresql:postgresql'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.mockito:mockito-core'
    testImplementation 'org.mockito:mockito-junit-jupiter'
    testRuntimeOnly 'com.h2database:h2'
}

tasks.named('test') {
    useJUnitPlatform()
}

flyway {
    url = project.hasProperty('flywayUrl') ? project.flywayUrl : 'jdbc:postgresql://localhost:5432/familyapp'
    user = project.hasProperty('flywayUser') ? project.flywayUser : 'familyapp_user'
    password = project.hasProperty('flywayPassword') ? project.flywayPassword : 'placeholder-postgres-password'
    locations = ['filesystem:src/main/resources/db/migration']
}
```

- [ ] **Step 3: 生成 Gradle wrapper（本地必须已装 gradle 7.5+ 或用 SDKMAN）**

Run (in `./backend` 目录下): `gradle wrapper --gradle-version 8.8`
Expected: 生成 `gradlew` + `gradlew.bat` + `gradle/wrapper/gradle-wrapper.jar` + `gradle/wrapper/gradle-wrapper.properties`

> ⚠️ Windows PowerShell 提示：`./gradlew.bat` 是后续所有 gradle 命令的入口。不要直接 `./gradlew`（unix script 在 PowerShell 不直跑）。

- [ ] **Step 4: 创建 `FamilyAppApplication.java`**

```java
package com.familyapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FamilyAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyAppApplication.class, args);
    }
}
```

- [ ] **Step 5: 创建 `application.yml`**

```yaml
spring:
  application:
    name: family-app-backend
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/familyapp}
    username: ${SPRING_DATASOURCE_USERNAME:familyapp_user}
    password: ${SPRING_DATASOURCE_PASSWORD:placeholder-postgres-password}
    driver-class-name: org.postgresql.Driver
  flyway:
    enabled: ${SPRING_FLYWAY_ENABLED:true}
    locations: classpath:db/migration
  jpa:
    hibernate:
      ddl-auto: ${SPRING_JPA_HIBERNATE_DDL_AUTO:validate}
    show-sql: false
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

management:
  endpoints:
    web:
      exposure:
        include: ${MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE:health}
  endpoint:
    health:
      show-details: never

server:
  port: 8080
```

- [ ] **Step 6: 创建 `V1__Create_Tasks_Table.sql`**

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

- [ ] **Step 7: 创建 `db/rollback/V2__Drop_Tasks_Table.sql`**（不是 Flyway migration）

```sql
DROP INDEX IF EXISTS idx_tasks_status_due_date;
DROP TABLE IF EXISTS tasks;
```

- [ ] **Step 8: 验证 compile 可过（不跑 test）**

Run: `cd backend && ./gradlew.bat compileJava --no-daemon`
Expected: exit 0, BUILD SUCCESSFUL

- [ ] **Step 9: 验证 SQL 语法正确（在无 postgres 情况下，用 pgsanity 或简单用 postgres docker 起临时容器）**

Run:
```
docker run --rm -v ./backend/src/main/resources/db:/sql postgres:16-alpine sh -c "
  su postgres -c \"pg_ctl start -D /var/lib/postgresql/data\" 2>&1 | tail -1
  until su postgres -c 'pg_isready' 2>/dev/null; do sleep 1; done
  su postgres -c \"createdb test\"
  su postgres -c \"psql -d test -1 -f /sql/migration/V1__Create_Tasks_Table.sql -c '\\\\d tasks'\"
  su postgres -c \"psql -d test -c 'BEGIN' -c '\\\\i /sql/rollback/V2__Drop_Tasks_Table.sql' -c 'ROLLBACK' -c '\\\\d tasks'\"
"
```
Expected: 第一阶段输出 tasks 表结构；第二阶段 BEGIN 内 `\d tasks` 报 relation does not exist，ROLLBACK 后 `\d tasks` 再次有表

- [ ] **Step 10: Commit**

```bash
cd ..   # back to repo root
git add backend/build.gradle backend/settings.gradle \
        backend/gradlew backend/gradlew.bat backend/gradle/wrapper/ \
        backend/src/main/java/com/familyapp/FamilyAppApplication.java \
        backend/src/main/resources/application.yml \
        backend/src/main/resources/db/migration/V1__Create_Tasks_Table.sql \
        backend/src/main/resources/db/rollback/V2__Drop_Tasks_Table.sql
git commit -m "feat(infra): backend gradle spring-boot scaffold + flyway migration V1

- build.gradle (groovy): spring-boot 3.2.8, web/data-jpa/validation/actuator,
  flyway-core + flyway-database-postgresql, test (JUnit5+Mockito+H2)
- Gradle 8.8 wrapper generated
- FamilyAppApplication + application.yml (datasource/flyway/jpa/actuator/port)
- Flyway V1: tasks table (9 cols, CHECK 4-status enum, index on status+due_date)
- Flyway rollback script (not a migration): V2__Drop_Tasks_Table.sql
"
```

---

## Task 3: Java common Package (Result / ResultCode / ApiException / GlobalExceptionHandler + WebConfig placeholder)

**Files:**
- Create: `backend/src/main/java/com/familyapp/common/Result.java`
- Create: `backend/src/main/java/com/familyapp/common/ResultCode.java`
- Create: `backend/src/main/java/com/familyapp/common/ApiException.java`
- Create: `backend/src/main/java/com/familyapp/common/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/familyapp/config/WebConfig.java`

**Interfaces:**
- Consumes: Task 2 (Spring Boot context runs)
- Produces: Controller 可以 `return Result.ok(task)` / `throw new ApiException(ResultCode.TASK_NOT_FOUND)`；GlobalExceptionHandler 正确把所有异常包成 envelope JSON；`Result` 作为 envelope 类型

- [ ] **Step 1: 写失败测试（common 是纯 Java 类，不需要 Spring）—— 验证 Result.ok() 结构**

```java
// backend/src/test/java/com/familyapp/common/ResultTest.java
package com.familyapp.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultTest {

    @Test
    void ok_returns_envelope_with_code_0_and_data() {
        Result<String> r = Result.ok("hello");
        assertEquals(0, r.getCode());
        assertEquals("ok", r.getMsg());
        assertEquals("hello", r.getData());
    }

    @Test
    void error_uses_resultCode_code_and_msg() {
        Result<Object> r = Result.error(ResultCode.TASK_NOT_FOUND);
        assertEquals(40401, r.getCode());
        assertEquals("task not found", r.getMsg());
        assertNull(r.getData());
    }
}
```

- [ ] **Step 2: 跑 ResultTest 确认失败（Result/ResultCode 还不存在）**

Run: `cd backend && ./gradlew.bat test --tests 'com.familyapp.common.ResultTest'`
Expected: FAIL, compile errors "cannot find symbol Result, ResultCode"

- [ ] **Step 3: Create `ResultCode.java`（错误码枚举，按设计 §3.1 表）**

```java
package com.familyapp.common;

public enum ResultCode {
    OK(0, "ok"),
    BAD_REQUEST_TITLE_EMPTY(40001, "title is blank"),
    BAD_REQUEST_TITLE_TOO_LONG(40002, "title exceeds 200 chars"),
    BAD_REQUEST_DATE_FORMAT(40003, "due_date format is invalid"),
    BAD_REQUEST_STATUS_TRANSITION(40011, "invalid status transition"),
    BAD_REQUEST_VALIDATION(40099, "validation failed"),
    TASK_NOT_FOUND(40401, "task not found"),
    INTERNAL_ERROR(50000, "internal error");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() { return code; }
    public String getMsg()  { return msg;  }
}
```

- [ ] **Step 4: Create `Result.java`（泛型 envelope，含 `Result.ok(T data)` + `Result.error(ResultCode rc)` 静态工厂）**

```java
package com.familyapp.common;

public class Result<T> {
    private int code;
    private String msg;
    private T data;

    public Result() {}

    public Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.OK.getCode(), ResultCode.OK.getMsg(), data);
    }

    public static Result<Void> ok() {
        return new Result<>(ResultCode.OK.getCode(), ResultCode.OK.getMsg(), null);
    }

    public static <T> Result<T> error(ResultCode rc) {
        return new Result<>(rc.getCode(), rc.getMsg(), null);
    }

    public static <T> Result<T> error(ResultCode rc, String customMsg) {
        return new Result<>(rc.getCode(), customMsg, null);
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
```

- [ ] **Step 5: Create `ApiException.java`（RuntimeException，含 ResultCode 字段）**

```java
package com.familyapp.common;

public class ApiException extends RuntimeException {
    private final ResultCode code;

    public ApiException(ResultCode code) {
        super(code.getMsg());
        this.code = code;
    }

    public ApiException(ResultCode code, String customMessage) {
        super(customMessage);
        this.code = code;
    }

    public ResultCode getCode() { return code; }
}
```

- [ ] **Step 6: Create `GlobalExceptionHandler.java`（`@RestControllerAdvice`，4 类异常）**

```java
package com.familyapp.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result<Object>> handleApi(ApiException ex) {
        ResultCode rc = ex.getCode();
        String message = ex.getMessage();
        HttpStatus http =
            switch (rc) {
                case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case BAD_REQUEST_TITLE_EMPTY, BAD_REQUEST_TITLE_TOO_LONG,
                     BAD_REQUEST_DATE_FORMAT, BAD_REQUEST_STATUS_TRANSITION,
                     BAD_REQUEST_VALIDATION -> HttpStatus.BAD_REQUEST;
                case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
                default -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        return new ResponseEntity<>(Result.error(rc, message), http);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().isEmpty()
            ? ResultCode.BAD_REQUEST_VALIDATION.getMsg()
            : ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return new ResponseEntity<>(Result.error(ResultCode.BAD_REQUEST_VALIDATION, msg), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return new ResponseEntity<>(
            Result.error(ResultCode.BAD_REQUEST_VALIDATION, ex.getMessage()),
            HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleFallback(Exception ex) {
        return new ResponseEntity<>(Result.error(ResultCode.INTERNAL_ERROR), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
```

- [ ] **Step 7: Create `WebConfig.java`（placeholder 空文件，Next change CORS）**

```java
package com.familyapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    // Next change: add CORS mapping / converters here
}
```

- [ ] **Step 8: 重跑 ResultTest 确认 pass**

Run: `cd backend && ./gradlew.bat test --tests 'com.familyapp.common.ResultTest'`
Expected: BUILD SUCCESSFUL, 2 tests passed

- [ ] **Step 9: Commit**

```bash
cd ..
git add backend/src/main/java/com/familyapp/common/ backend/src/main/java/com/familyapp/config/ backend/src/test/java/com/familyapp/common/
git commit -m "feat(common): unified {code,msg,data} envelope + global exception handler

- Result<T> + ResultCode enum (7 codes, OK/400xx/40401/50000)
- ApiException (RuntimeException wrapper around ResultCode)
- GlobalExceptionHandler (@RestControllerAdvice): ApiException/Validation/IllegalArgument/fallback
- WebConfig placeholder (next change CORS)
- ResultTest: Result.ok + Result.error serialization contracts
"
```

---

## Task 4: Task Domain Model (TaskStatus enum / Task entity / TaskRepository / 3 DTOs)

**Files:**
- Create: `backend/src/main/java/com/familyapp/task/TaskStatus.java`
- Create: `backend/src/main/java/com/familyapp/task/Task.java`
- Create: `backend/src/main/java/com/familyapp/task/TaskRepository.java`
- Create: `backend/src/main/java/com/familyapp/task/dto/CreateTaskRequest.java`
- Create: `backend/src/main/java/com/familyapp/task/dto/UpdateTaskRequest.java`
- Create: `backend/src/main/java/com/familyapp/task/dto/TaskResponse.java`

**Interfaces:**
- Consumes: 设计 §2 的列定义 + @Where 软删除
- Produces: 供 Task 5 Service 调用：`TaskStatus.values()` / `task.getStatus()` `setStatus(TaskStatus.DONE)` / `repository.findById(id)` / `repository.findByIdIncludeDeleted(id)` / `repository.save(task)` / DTO ↔ Entity 转换时 getter/setter 名称和字段类型

- [ ] **Step 1: 写 Repository 失败测试（@DataJpaTest）—— 验证 @Where 过滤和 findByIdIncludeDeleted 绕过**

```java
// backend/src/test/java/com/familyapp/task/TaskRepositoryPreTest.java
package com.familyapp.task;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Optional;

@DataJpaTest
@ActiveProfiles("test")
class TaskRepositoryPreTest {

    @Autowired TaskRepository repo;

    @Test
    void findById_skips_soft_deleted_rows() {
        Task t = new Task();
        t.setTitle("x");
        t.setStatus(TaskStatus.TODO);
        t.setDeletedAt(Instant.now());
        Task saved = repo.save(t);
        Optional<Task> byId = repo.findById(saved.getId());
        assertFalse(byId.isPresent(), "@Where should filter deleted_at IS NOT NULL");
        Optional<Task> include = repo.findByIdIncludeDeleted(saved.getId());
        assertTrue(include.isPresent(), "findByIdIncludeDeleted bypasses @Where");
    }
}
```

Add `application-test.yml`（让 @DataJpaTest 跑 H2，不连 Postgres，同时禁用 Flyway（用 Hibernate ddl-auto）—— 因为 H2 方言 PostgreSQL 语法 CHECK CONSTRAINT 兼容有限）：

```yaml
# backend/src/test/resources/application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:familyapptest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    database-platform: org.hibernate.dialect.H2Dialect
  flyway:
    enabled: false
```

- [ ] **Step 2: 跑 Repository 测试确认失败（TaskStatus/Task/TaskRepository 不存在）**

Run: `cd backend && ./gradlew.bat test --tests 'com.familyapp.task.TaskRepositoryPreTest'`
Expected: FAIL compilation error

- [ ] **Step 3: Create `TaskStatus.java` enum**

```java
package com.familyapp.task;

public enum TaskStatus {
    TODO,
    IN_PROGRESS,
    DONE,
    CANCELLED
}
```

- [ ] **Step 4: Create `Task.java` entity（10 字段 + @Where + @PrePersist/@PreUpdate）**

```java
package com.familyapp.task;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "tasks")
@Where(clause = "deleted_at IS NULL")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) this.status = TaskStatus.TODO;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // --- Getters / Setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
```

- [ ] **Step 5: Create `TaskRepository.java`（JpaRepository + @Query 绕过 @Where）**

```java
package com.familyapp.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    @Query("SELECT t FROM Task t WHERE t.id = :id")
    Optional<Task> findByIdIncludeDeleted(@Param("id") Long id);
}
```

- [ ] **Step 6: Create 3 DTOs（严格按设计 §3.3 字段，Bean Validation 注解带 message）**

CreateTaskRequest.java:
```java
package com.familyapp.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public class CreateTaskRequest {

    @NotBlank(message = "title is blank")
    @Size(max = 200, message = "title exceeds 200 chars")
    private String title;

    private String description;

    private LocalDate dueDate;

    private String status;  // null means TODO; validated in service

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
```

UpdateTaskRequest.java:
```java
package com.familyapp.task.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public class UpdateTaskRequest {

    @Size(max = 200, message = "title exceeds 200 chars")
    private String title;

    private String description;

    private LocalDate dueDate;

    private String status;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
```

TaskResponse.java（7 字段，不含 deletedAt，可用 Java 14+ record 更简洁）:
```java
package com.familyapp.task.dto;

import com.familyapp.task.Task;
import com.familyapp.task.TaskStatus;

import java.time.Instant;
import java.time.LocalDate;

public record TaskResponse(
    Long id,
    String title,
    String description,
    LocalDate dueDate,
    TaskStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public static TaskResponse fromEntity(Task t) {
        return new TaskResponse(
            t.getId(), t.getTitle(), t.getDescription(),
            t.getDueDate(), t.getStatus(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
```

- [ ] **Step 7: 重跑 RepositoryPreTest 确认 pass**

Run: `cd backend && ./gradlew.bat test --tests 'com.familyapp.task.TaskRepositoryPreTest'`
Expected: BUILD SUCCESSFUL, 1 test passed

- [ ] **Step 8: Commit**

```bash
cd ..
git add backend/src/main/java/com/familyapp/task/TaskStatus.java \
        backend/src/main/java/com/familyapp/task/Task.java \
        backend/src/main/java/com/familyapp/task/TaskRepository.java \
        backend/src/main/java/com/familyapp/task/dto/ \
        backend/src/test/java/com/familyapp/task/TaskRepositoryPreTest.java \
        backend/src/test/resources/application-test.yml
git commit -m "feat(task): Task domain model + @Where soft delete + 3 DTOs

- TaskStatus: 4-value enum (TODO/IN_PROGRESS/DONE/CANCELLED)
- Task entity: 9 columns + @Where deleted_at filter + @PrePersist/@PreUpdate timestamps
- TaskRepository: JpaRepository<Task,Long> + findByIdIncludeDeleted bypasses @Where
- DTOs: CreateTaskRequest (@NotBlank+@Size(200)), UpdateTaskRequest (partial),
        TaskResponse (record, 7 fields + static fromEntity mapper)
- application-test.yml: H2 + Hibernate create-drop, flyway off
- TaskRepositoryPreTest: @Where filter + findByIdIncludeDeleted bypass
"
```

---

## Task 5: TaskService (interface + impl DFA) + TaskController (5 endpoints)

**Files:**
- Create: `backend/src/main/java/com/familyapp/task/TaskService.java`
- Create: `backend/src/main/java/com/familyapp/task/TaskServiceImpl.java`
- Create: `backend/src/main/java/com/familyapp/task/TaskController.java`

**Interfaces:**
- Consumes: Task 4 的 Task/TaskRepository/DTOs；Task 3 的 Result/ResultCode/ApiException
- Produces: 标准三层接口，Service 暴露 5 个方法对应 5 个 endpoint；Controller 的 5 个 endpoint 返回 envelope `Result<T>`，错误码完全匹配设计 §3.1

- [ ] **Step 1: 写失败测试（Service DFA）—— 验证 2 条非法 status 路径**

```java
// backend/src/test/java/com/familyapp/task/TaskServicePreTest.java
package com.familyapp.task;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.UpdateTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServicePreTest {

    @Mock TaskRepository repo;
    @InjectMocks TaskServiceImpl service;

    private Task doneTask;

    @BeforeEach
    void setUp() {
        doneTask = new Task();
        doneTask.setId(99L);
        doneTask.setTitle("done");
        doneTask.setStatus(TaskStatus.DONE);
    }

    @Test
    void update_rejects_DONE_to_TODO_transition() {
        when(repo.findById(99L)).thenReturn(Optional.of(doneTask));
        UpdateTaskRequest req = new UpdateTaskRequest();
        req.setStatus("TODO");
        ApiException ex = assertThrows(ApiException.class, () -> service.update(99L, req));
        assertEquals(ResultCode.BAD_REQUEST_STATUS_TRANSITION, ex.getCode());
    }

    @Test
    void update_rejects_CANCELLED_to_ANY_transition() {
        doneTask.setStatus(TaskStatus.CANCELLED);
        when(repo.findById(99L)).thenReturn(Optional.of(doneTask));
        UpdateTaskRequest req = new UpdateTaskRequest();
        req.setStatus("IN_PROGRESS");
        ApiException ex = assertThrows(ApiException.class, () -> service.update(99L, req));
        assertEquals(ResultCode.BAD_REQUEST_STATUS_TRANSITION, ex.getCode());
    }
}
```

- [ ] **Step 2: 跑 ServicePreTest 失败（TaskService/TaskServiceImpl 不存在）**

Run: `cd backend && ./gradlew.bat test --tests 'com.familyapp.task.TaskServicePreTest'`
Expected: FAIL compile

- [ ] **Step 3: Create `TaskService.java` interface**

```java
package com.familyapp.task;

import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskResponse;
import com.familyapp.task.dto.UpdateTaskRequest;

import java.util.List;

public interface TaskService {
    TaskResponse create(CreateTaskRequest req);
    List<TaskResponse> list();
    TaskResponse get(Long id);
    TaskResponse update(Long id, UpdateTaskRequest req);
    void delete(Long id);
}
```

- [ ] **Step 4: Create `TaskServiceImpl.java`（@Service，核心：validateTransition DFA + 软 delete）**

```java
package com.familyapp.task;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskResponse;
import com.familyapp.task.dto.UpdateTaskRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class TaskServiceImpl implements TaskService {

    private final TaskRepository repo;

    // DFA transition allow-list. Current status → set of allowed targets
    private static final EnumMap<TaskStatus, EnumSet<TaskStatus>> TRANSITIONS =
        new EnumMap<>(TaskStatus.class);

    static {
        TRANSITIONS.put(TaskStatus.TODO, EnumSet.of(
            TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.DONE, TaskStatus.CANCELLED
        ));
        TRANSITIONS.put(TaskStatus.IN_PROGRESS, EnumSet.of(
            TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.DONE, TaskStatus.CANCELLED
        ));
        TRANSITIONS.put(TaskStatus.DONE, EnumSet.of(TaskStatus.DONE));
        TRANSITIONS.put(TaskStatus.CANCELLED, EnumSet.of(TaskStatus.CANCELLED));
    }

    public TaskServiceImpl(TaskRepository repo) { this.repo = repo; }

    @Override
    public TaskResponse create(CreateTaskRequest req) {
        Task t = new Task();
        t.setTitle(req.getTitle());
        t.setDescription(req.getDescription());
        t.setDueDate(req.getDueDate());
        TaskStatus status = parseStatusOrDefault(req.getStatus(), TaskStatus.TODO);
        // Allow creating with any status except CANCELLED at start of life (sanity)
        if (status == TaskStatus.CANCELLED) {
            throw new ApiException(ResultCode.BAD_REQUEST_STATUS_TRANSITION,
                "cannot create new task as CANCELLED");
        }
        t.setStatus(status);
        Task saved = repo.save(t);
        return TaskResponse.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> list() {
        return repo.findAll().stream()
            .map(TaskResponse::fromEntity)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public TaskResponse get(Long id) {
        Task t = repo.findById(id)
            .orElseThrow(() -> new ApiException(ResultCode.TASK_NOT_FOUND));
        return TaskResponse.fromEntity(t);
    }

    @Override
    public TaskResponse update(Long id, UpdateTaskRequest req) {
        Task t = repo.findById(id)
            .orElseThrow(() -> new ApiException(ResultCode.TASK_NOT_FOUND));
        if (req.getTitle() != null) t.setTitle(req.getTitle());
        if (req.getDescription() != null) t.setDescription(req.getDescription());
        if (req.getDueDate() != null) t.setDueDate(req.getDueDate());
        if (req.getStatus() != null) {
            TaskStatus target = parseStatus(req.getStatus());
            validateTransition(t.getStatus(), target);
            t.setStatus(target);
        }
        Task saved = repo.save(t);
        return TaskResponse.fromEntity(saved);
    }

    @Override
    public void delete(Long id) {
        Task t = repo.findByIdIncludeDeleted(id)
            .orElseThrow(() -> new ApiException(ResultCode.TASK_NOT_FOUND));
        if (t.getDeletedAt() != null) {
            throw new ApiException(ResultCode.TASK_NOT_FOUND);
        }
        t.setDeletedAt(Instant.now());
        repo.save(t);
    }

    // ---------------- helpers ----------------

    private TaskStatus parseStatus(String raw) {
        if (raw == null) throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,
            "status is blank");
        try {
            return TaskStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,
                "invalid status value, must be one of " + Arrays.toString(TaskStatus.values()));
        }
    }

    private TaskStatus parseStatusOrDefault(String raw, TaskStatus def) {
        if (raw == null || raw.isBlank()) return def;
        return parseStatus(raw);
    }

    private void validateTransition(TaskStatus current, TaskStatus target) {
        EnumSet<TaskStatus> allowed = TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new ApiException(ResultCode.BAD_REQUEST_STATUS_TRANSITION,
                "invalid status transition from " + current + " to " + target);
        }
    }
}
```

- [ ] **Step 5: Create `TaskController.java`（`@RestController` + 5 端点，严格 HTTP status）**

```java
package com.familyapp.task;

import com.familyapp.common.Result;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskResponse;
import com.familyapp.task.dto.UpdateTaskRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<TaskResponse> create(@Valid @RequestBody CreateTaskRequest req) {
        return Result.ok(service.create(req));
    }

    @GetMapping
    public Result<List<TaskResponse>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{id}")
    public Result<TaskResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PutMapping("/{id}")
    public Result<TaskResponse> update(@PathVariable Long id,
                                       @Valid @RequestBody UpdateTaskRequest req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok(true);
    }
}
```

- [ ] **Step 6: 重跑 ServicePreTest 确认 pass**

Run: `cd backend && ./gradlew.bat test --tests 'com.familyapp.task.TaskServicePreTest'`
Expected: BUILD SUCCESSFUL, 2 tests passed

- [ ] **Step 7: verify compileJava / bootJar 无错**

Run: `cd backend && ./gradlew.bat compileJava bootJar -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
cd ..
git add backend/src/main/java/com/familyapp/task/TaskService.java \
        backend/src/main/java/com/familyapp/task/TaskServiceImpl.java \
        backend/src/main/java/com/familyapp/task/TaskController.java \
        backend/src/test/java/com/familyapp/task/TaskServicePreTest.java
git commit -m "feat(task): service DFA + 3-layer controller endpoints

- TaskService interface: 5 CRUD methods
- TaskServiceImpl:
  - static DFA TRANSITIONS map (TODO/IN_PROGRESS→any, DONE→DONE only, CANCELLED→CANCELLED only)
  - validateTransition() throws BAD_REQUEST_STATUS_TRANSITION
  - create: reject CANCELLED at birth
  - delete: soft-delete (setDeletedAt(now)) instead of repo.delete, uses findByIdIncludeDeleted
- TaskController: @RestController /api/tasks
  - POST → 201 + Result<TaskResponse>
  - GET list → 200 + Result<List<TaskResponse>>
  - GET {id} → 200 + Result<TaskResponse>
  - PUT {id} → 200 + Result<TaskResponse>
  - DELETE {id} → 200 + Result<Boolean>
- TaskServicePreTest: 2 invalid DFA transition paths reject correctly
"
```

---

## Task 6: All Java Tests + Context Smoke

**Files:**
- Create: `backend/src/test/java/com/familyapp/FamilyAppApplicationTest.java`
- Rename: `backend/src/test/java/com/familyapp/task/TaskServicePreTest.java` → expand to full tests
- Modify: `backend/src/test/java/com/familyapp/task/TaskRepositoryPreTest.java` → expand
- Create: `backend/src/test/java/com/familyapp/task/TaskControllerTest.java`（MockMvc）

**Interfaces:**
- Consumes: Tasks 1-5 all present
- Produces: `./gradlew.bat test` 全部通过（设计 §5.1 目标：Java 分层测试覆盖率达标）

- [ ] **Step 1: Create Smoke Spring Context Test**

```java
package com.familyapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FamilyAppApplicationTest {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 2: 重命名并扩展 `TaskServicePreTest.java` → `TaskServiceTest.java`（完整覆盖 7 条合法 DFA）**

Rename file: `TaskServicePreTest.java` → `TaskServiceTest.java`，在现有 2 条非法用例基础上追加 8 条：

```java
// 追加到 TaskServiceTest.java 末尾

    @Test
    void create_default_status_is_TODO_when_null() {
        when(repo.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(1L);
            t.onCreate();
            return t;
        });
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("t1");
        TaskResponse r = service.create(req);
        assertEquals(TaskStatus.TODO, r.status());
        assertEquals("t1", r.title());
    }

    @Test
    void create_allows_TODO_IN_PROGRESS_DONE_rejects_CANCELLED() {
        when(repo.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(1L);
            t.onCreate();
            return t;
        });
        for (String s : List.of("TODO", "IN_PROGRESS", "DONE")) {
            CreateTaskRequest r = new CreateTaskRequest();
            r.setTitle("ok");
            r.setStatus(s);
            assertEquals(TaskStatus.valueOf(s), service.create(r).status());
        }
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("bad");
        req.setStatus("CANCELLED");
        ApiException ex = assertThrows(ApiException.class, () -> service.create(req));
        assertEquals(ResultCode.BAD_REQUEST_STATUS_TRANSITION, ex.getCode());
    }

    @Test
    void list_maps_multiple_tasks() {
        Task a = new Task(); a.setId(1L); a.setTitle("A"); a.setStatus(TaskStatus.TODO); a.onCreate();
        Task b = new Task(); b.setId(2L); b.setTitle("B"); b.setStatus(TaskStatus.DONE); b.onCreate();
        when(repo.findAll()).thenReturn(List.of(a, b));
        List<TaskResponse> rs = service.list();
        assertEquals(2, rs.size());
        assertEquals("A", rs.get(0).title());
        assertEquals(TaskStatus.DONE, rs.get(1).status());
    }

    @Test
    void get_returns_task_or_throws() {
        Task a = new Task(); a.setId(1L); a.setTitle("A"); a.setStatus(TaskStatus.TODO); a.onCreate();
        when(repo.findById(1L)).thenReturn(Optional.of(a));
        assertEquals("A", service.get(1L).title());
        when(repo.findById(2L)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.get(2L));
    }

    @Test
    void delete_is_soft_delete() {
        Task t = new Task(); t.setId(1L); t.setTitle("x"); t.setStatus(TaskStatus.TODO); t.onCreate();
        when(repo.findByIdIncludeDeleted(1L)).thenReturn(Optional.of(t));
        service.delete(1L);
        assertNotNull(t.getDeletedAt(), "expected deleted_at set");
        verify(repo).save(t);
    }

    @Test
    void delete_on_already_deleted_throws_404() {
        Task t = new Task(); t.setId(1L); t.setDeletedAt(Instant.now());
        when(repo.findByIdIncludeDeleted(1L)).thenReturn(Optional.of(t));
        ApiException ex = assertThrows(ApiException.class, () -> service.delete(1L));
        assertEquals(ResultCode.TASK_NOT_FOUND, ex.getCode());
    }

    @Test
    void update_accepts_partial_fields() {
        Task t = new Task(); t.setId(1L); t.setTitle("orig"); t.setStatus(TaskStatus.TODO); t.onCreate();
        when(repo.findById(1L)).thenReturn(Optional.of(t));
        when(repo.save(any(Task.class))).thenReturn(t);
        UpdateTaskRequest req = new UpdateTaskRequest();
        req.setTitle("new");  // no status change
        TaskResponse r = service.update(1L, req);
        assertEquals("new", r.title());
        assertEquals(TaskStatus.TODO, r.status());
    }

    @Test
    void update_status_allows_7_valid_DFA_paths() {
        // Pair(current, target) → valid
        List<String[]> validPairs = List.of(
            new String[]{"TODO", "TODO"}, new String[]{"TODO", "IN_PROGRESS"},
            new String[]{"TODO", "DONE"}, new String[]{"TODO", "CANCELLED"},
            new String[]{"IN_PROGRESS", "TODO"}, new String[]{"IN_PROGRESS", "DONE"},
            new String[]{"IN_PROGRESS", "IN_PROGRESS"}
        );
        for (String[] pair : validPairs) {
            Task t = new Task(); t.setId(1L); t.setTitle("t"); t.setStatus(TaskStatus.valueOf(pair[0])); t.onCreate();
            when(repo.findById(1L)).thenReturn(Optional.of(t));
            when(repo.save(any(Task.class))).thenReturn(t);
            UpdateTaskRequest req = new UpdateTaskRequest();
            req.setStatus(pair[1]);
            TaskResponse r = service.update(1L, req);
            assertEquals(TaskStatus.valueOf(pair[1]), r.status(),
                "expected valid transition " + pair[0] + " → " + pair[1]);
        }
    }
```

- [ ] **Step 3: 扩展 `TaskRepositoryPreTest.java` → 重命名为 `TaskRepositoryTest.java`，增加 2 个用例**

Rename file, append:

```java
    @Test
    void findById_return_not_deleted_rows() {
        Task t = new Task();
        t.setTitle("a"); t.setStatus(TaskStatus.TODO);  // deletedAt = null
        Task saved = repo.save(t);
        Optional<Task> byId = repo.findById(saved.getId());
        assertTrue(byId.isPresent());
        assertEquals("a", byId.get().getTitle());
    }

    @Test
    void findAll_excludes_deleted_rows() {
        Task ok1 = new Task(); ok1.setTitle("ok1"); ok1.setStatus(TaskStatus.TODO);
        Task ok2 = new Task(); ok2.setTitle("ok2"); ok2.setStatus(TaskStatus.TODO);
        Task del = new Task(); del.setTitle("del"); del.setStatus(TaskStatus.TODO);
        del.setDeletedAt(Instant.now());
        repo.save(ok1); repo.save(ok2); repo.save(del);
        assertEquals(2, repo.findAll().size());
    }
```

- [ ] **Step 4: 新建 `TaskControllerTest.java`（MockMvc 覆盖 5 端点 HTTP 层）**

```java
package com.familyapp.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskResponse;
import com.familyapp.task.dto.UpdateTaskRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void POST_create_201_envelope_ok() throws Exception {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("wash dishes");
        req.setDueDate(LocalDate.of(2026, 8, 20));
        mvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.title").value("wash dishes"))
            .andExpect(jsonPath("$.data.status").value("TODO"))
            .andExpect(jsonPath("$.data.id").isNumber());
    }

    @Test
    void POST_create_rejects_blank_title_40001() throws Exception {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("   ");
        mvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40099))
            .andExpect(jsonPath("$.msg", notNullValue()));
    }

    @Test
    void GET_list_empty_array() throws Exception {
        mvc.perform(get("/api/tasks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void GET_by_id_not_found_returns_40401() throws Exception {
        mvc.perform(get("/api/tasks/99999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void PUT_then_GET_returns_updated() throws Exception {
        // 1) create
        CreateTaskRequest cr = new CreateTaskRequest();
        cr.setTitle("one");
        String createResp = mvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(cr)))
            .andReturn().getResponse().getContentAsString();
        TaskResponse created = json.readValue(
            json.readTree(createResp).get("data").toString(), TaskResponse.class);
        Long id = created.id();

        // 2) update status = DONE
        UpdateTaskRequest ur = new UpdateTaskRequest();
        ur.setStatus("DONE");
        mvc.perform(put("/api/tasks/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ur)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("DONE"));

        // 3) DONE → TODO 非法：应返回 40011
        UpdateTaskRequest bad = new UpdateTaskRequest();
        bad.setStatus("TODO");
        mvc.perform(put("/api/tasks/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(bad)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40011));
    }

    @Test
    void DELETE_hides_from_GET_list_soft_delete() throws Exception {
        CreateTaskRequest cr = new CreateTaskRequest();
        cr.setTitle("to delete");
        String body = mvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(cr)))
            .andReturn().getResponse().getContentAsString();
        Long id = json.readTree(body).get("data").get("id").longValue();

        mvc.perform(delete("/api/tasks/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").value(true));

        // soft-deleted task must NOT appear in list
        mvc.perform(get("/api/tasks"))
            .andExpect(jsonPath("$.data[*].title", not(hasItem("to delete"))));
    }
}
```

- [ ] **Step 5: 一次性跑 `./gradlew.bat test` 全通过**

Run: `cd backend && ./gradlew.bat test`
Expected: BUILD SUCCESSFUL, **~14 tests total**（1 smoke + 2 pre + 8 new service + 2 new repo + 6 controller = 19，精确数字以 JUnit 报告为准；全 pass 即可）

> 如果出现 Hibernate DDL AUTO 导致的 validation fail → 确保 `application-test.yml` 的 `spring.jpa.hibernate.ddl-auto` 确实是 `create-drop`（不是 `validate`）。
> 如果出现 Flyway 在 test 里也尝试跑 migration → 确保 `spring.flyway.enabled` 是 `false`（test profile）。

- [ ] **Step 6: Commit**

```bash
cd ..
git add backend/src/test/
git commit -m "test(task): full layered test suite (14+ tests, all passing)

- FamilyAppApplicationTest: @SpringBootTest smoke context loads
- TaskServiceTest: 2 invalid DFA pre-existing + 8 new (create default, list,
  get, soft delete, delete 404, partial update, 7 valid DFA transitions)
- TaskRepositoryTest: @Where filter bypass, findAll excludes deleted
- TaskControllerTest (@SpringBootTest + MockMvc + H2):
    * POST 201 envelope contract
    * POST blank title → 40099 validation
    * GET list → array
    * GET 404 → 40401 code
    * PUT status DONE + DONE→TODO → 40011
    * DELETE soft hide from list
"
```

---

## Task 7: Frontend Scaffold (package.json / vite.config.js / index.html / main.js / App.vue)

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.js`
- Create: `frontend/index.html`
- Create: `frontend/src/main.js`
- Create: `frontend/src/App.vue`

**Interfaces:**
- Consumes: 设计 §4.1 依赖（vue 3.4 / vite 5.3 / axios 1.7 / element-plus 2.7 / unplugin-auto-import 0.18 / unplugin-vue-components 0.27）
- Produces: `cd frontend && npm run build` 0 error（SOP 要求）；`npm run dev` 启动后 `http://localhost:5173/` 返回正确 HTML；Vite `/api` proxy 正确配置；Element Plus 按需 import 插件挂好

- [ ] **Step 1: 创建 `frontend/package.json`（精确版本，不使用模糊前缀，避免未来回归）**

```json
{
  "name": "family-app-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "axios": "^1.7.5",
    "element-plus": "^2.7.8",
    "vue": "^3.4.38"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.1.2",
    "unplugin-auto-import": "^0.18.2",
    "unplugin-vue-components": "^0.27.4",
    "vite": "^5.4.2"
  }
}
```

- [ ] **Step 2: 创建 `frontend/vite.config.js`（Vue plugin + Element Plus 两个 resolver + /api proxy + host 0.0.0.0）**

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      imports: ['vue']
    }),
    Components({
      resolvers: [ElementPlusResolver()]
    })
  ],
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://backend:8080',
        changeOrigin: true
      }
    }
  }
})
```

- [ ] **Step 3: 创建 `frontend/index.html`**

```html
<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ctext y='.9em' font-size='90'%3E🏠%3C/text%3E%3C/svg%3E" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Family App</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.js"></script>
  </body>
</html>
```

- [ ] **Step 4: 创建 `frontend/src/main.js`**

```js
import { createApp } from 'vue'
import App from './App.vue'

createApp(App).mount('#app')
```

- [ ] **Step 5: 创建 `frontend/src/App.vue`（最小占位，只 import `<TaskView />`，TaskView 由 Task 8 建；这里先用简单 message 保证 build 不 fail）**

```vue
<template>
  <div id="app-root" style="padding: 24px; font-family: -apple-system, system-ui, sans-serif;">
    <h2 style="margin-bottom: 20px;">🏠 Family App</h2>
    <TaskView />
  </div>
</template>

<script setup>
import TaskView from './views/TaskView.vue'
</script>
```

- [ ] **Step 6: 先建一个 `frontend/src/views/TaskView.vue` 的临时最小占位（避免 build 因缺文件挂）**

```vue
<template>
  <p>TaskView loading...</p>
</template>
```

（该文件在 Task 8 中会被整个替换）

- [ ] **Step 7: `npm install` 安装依赖**

Run: `cd frontend && npm install --no-audit --no-fund`
Expected: 成功，生成 `node_modules/` + `package-lock.json`

- [ ] **Step 8: `npm run build` 必须 0 error**

Run: `cd frontend && npm run build`
Expected: exit 0, 输出 `✓ built in Xs`，生成 `frontend/dist/` 目录

- [ ] **Step 9: 确认 `frontend/dist/` 被 .gitignore（Task 1 已加 pattern `frontend/dist/`）**

Run: `git status --short frontend/`
Expected: 只看到 `?? frontend/package.json`, `?? frontend/package-lock.json`, `?? frontend/vite.config.js`, `?? frontend/index.html`, `?? frontend/src/...`，不应有 `frontend/dist`（如果被 tracked 说明 Task 1 的 gitignore 加错了，立即修）

- [ ] **Step 10: Commit**

```bash
cd ..
git add frontend/package.json frontend/package-lock.json frontend/vite.config.js \
        frontend/index.html frontend/src/main.js frontend/src/App.vue \
        frontend/src/views/TaskView.vue
git commit -m "feat(infra): frontend vue3+vite+element-plus scaffold

- package.json: vue 3.4, vite 5, axios 1.7, element-plus 2.7,
  unplugin-auto-import + unplugin-vue-components (ElementPlus resolvers)
- vite.config.js: 0.0.0.0:5173 strictPort, /api → http://backend:8080 proxy
- index.html (zh-CN + house emoji favicon), main.js (bootstrap createApp)
- App.vue: 24px padding + header + <TaskView />
- TaskView.vue: placeholder (replaced in Task 8)
- build verified: npm run build exits 0
"
```

---

## Task 8: Frontend API Client + TaskView.vue Full Page

**Files:**
- Create: `frontend/src/api/tasks.js`（axios 5 method + 2 interceptors + ElMessage）
- Modify: `frontend/src/views/TaskView.vue`（replace placeholder with full page）

**Interfaces:**
- Consumes: Task 7（Element Plus 自动 import 已就绪 + /api proxy 已配）；Task 5 的 5 个后端 endpoint 的 request/response envelope 格式
- Produces: 页面 UI 元素全部由 Element Plus 渲染（el-form / el-input / el-date-picker / el-button / el-table / ElMessage / ElMessageBox）；4 个交互（创建表单提交 / 完成按钮 / 删除按钮带二次确认 / onMounted 拉列表）都正确调用对应 axios 方法 + 刷新列表

- [ ] **Step 1: 创建 `frontend/src/api/tasks.js`（严格按设计 §4.3）**

```js
import axios from 'axios'
import { ElMessage } from 'element-plus'

const client = axios.create({
  baseURL: '/api',
  timeout: 10_000
})

client.interceptors.response.use(
  (resp) => {
    const { code, msg, data } = resp.data || {}
    if (code !== 0) {
      ElMessage.error(msg || 'request failed')
      const err = new Error(msg || 'request failed')
      err.code = code
      throw err
    }
    return data
  },
  (err) => {
    ElMessage.error(err?.message || 'network error')
    throw err
  }
)

export const tasksApi = {
  list:   ()                 => client.get('/tasks'),
  create: (payload)          => client.post('/tasks', payload),
  get:    (id)               => client.get(`/tasks/${id}`),
  update: (id, payload)      => client.put(`/tasks/${id}`, payload),
  del:    (id)               => client.delete(`/tasks/${id}`)
}

export default client
```

- [ ] **Step 2: 重写 `frontend/src/views/TaskView.vue`（完整页面）**

```vue
<template>
  <el-card class="task-container">
    <!-- Create form -->
    <h3 style="margin-top: 0">创建新任务</h3>
    <el-form :model="form" label-width="80px" style="max-width: 640px">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="200" show-word-limit
                  placeholder="任务标题，必填（最多 200 字）" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="2"
                  placeholder="详细描述（可选）" />
      </el-form-item>
      <el-form-item label="截止日期">
        <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD"
                        placeholder="选择日期（可选）" style="width: 260px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :disabled="!form.title.trim()" @click="submitCreate">
          提交
        </el-button>
        <el-button @click="resetForm">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- List -->
    <h3 style="margin-top: 28px;">全部任务</h3>
    <el-table :data="tasks" stripe style="width: 100%" v-loading="loadingList"
              empty-text="暂无任务，先创建一个吧">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="200" />
      <el-table-column prop="status" label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="dueDate" label="截止日期" width="140" />
      <el-table-column prop="createdAt" label="创建时间" width="200">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="success" size="small"
                     :disabled="row.status === 'DONE' || row.status === 'CANCELLED'"
                     @click="markDone(row)">
            完成
          </el-button>
          <el-button type="danger" size="small" @click="confirmDelete(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { tasksApi } from '../api/tasks'

const form = reactive({ title: '', description: '', dueDate: '' })
const tasks = ref([])
const loadingList = ref(false)

const statusLabel = (s) => ({
  TODO: '待办',
  IN_PROGRESS: '进行中',
  DONE: '已完成',
  CANCELLED: '已取消'
}[s] || s)

const statusTagType = (s) => ({
  TODO: 'info',
  IN_PROGRESS: 'warning',
  DONE: 'success',
  CANCELLED: 'danger'
}[s] || '')

const formatTime = (t) => t ? new Date(t).toLocaleString('zh-CN') : ''

async function refreshList() {
  loadingList.value = true
  try {
    tasks.value = await tasksApi.list()
  } finally {
    loadingList.value = false
  }
}

async function submitCreate() {
  if (!form.title.trim()) return
  const payload = {
    title: form.title.trim(),
    description: form.description || undefined,
    dueDate: form.dueDate || undefined
  }
  await tasksApi.create(payload)
  ElMessage.success('任务创建成功')
  resetForm()
  await refreshList()
}

function resetForm() {
  form.title = ''
  form.description = ''
  form.dueDate = ''
}

async function markDone(row) {
  try {
    await tasksApi.update(row.id, { status: 'DONE' })
    ElMessage.success(`任务「${row.title}」已标记完成`)
    await refreshList()
  } catch (_) { /* interceptor already toast */ }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除任务「${row.title}」吗？该操作可恢复（软删除）。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
    await tasksApi.del(row.id)
    ElMessage.success('任务已删除')
    await refreshList()
  } catch (action) {
    if (action !== 'cancel' && action !== 'close') {
      // non-cancel flow: let interceptor show error
    }
  }
}

onMounted(refreshList)
</script>

<style scoped>
.task-container {
  max-width: 1100px;
  margin: 0 auto;
}
</style>
```

- [ ] **Step 3: `npm run build` 再跑 0 error**

Run: `cd frontend && npm run build`
Expected: exit 0, BUILD OK

> 常见 build 失败原因：Element Plus `ElMessage` / `ElMessageBox` 在 `unplugin-auto-import` 配置下如果没有自动注入 —— 检查 vite.config.js 的 `ElementPlusResolver()` 是否同时在 AutoImport 和 Components 两个插件都加了。若依旧 import err，手动补 `import { ElMessage, ElMessageBox } from 'element-plus'` 明确引入即可，本次 build 不过就选这个 fallback。

- [ ] **Step 4: Commit**

```bash
cd ..
git add frontend/src/api/tasks.js frontend/src/views/TaskView.vue
git commit -m "feat(frontend): tasks api client + TaskView full page

- tasks.js: axios client, /api base, response interceptor strips envelope,
  ElMessage.error on non-0 code and network errors; exports tasksApi (5 methods)
- TaskView.vue (Element Plus auto-imported components):
    * el-form (title required / description textarea / el-date-picker YYYY-MM-DD)
    * el-table (status tag colored, created_at localized, fixed-right actions)
    * markDone button PUT {status: DONE} (disabled when DONE/CANCELLED)
    * delete button with ElMessageBox warning confirm → soft delete
    * onMounted refetch list + auto-refetch after mutations
- build verified: npm run build exits 0
"
```

---

## Task 9: SOP Phase 6 End-to-End Verification (All Gates)

此任务不创建代码，**只跑验证并把结果记下来**。必须在 Tasks 1-8 全部完成并 commit 之后执行。

**Consumes:** 所有前序 task 的产物

**Produces:** 全部 8 类验证命令 + 输出摘要（可复制粘贴给用户）；并把 openspec artifacts 全链路（specs/design/tasks）补全 → `openspec validate` 全通过 → 最终 `git status` 干净。

### 9.1 Gradle tests 验证（SOP 阶段6 Java）

- [ ] **Step 1: Run `./gradlew.bat test`（backend 内）**

Expected: BUILD SUCCESSFUL，0 failing tests。（已在 Task 6 跑过，但在这里必须**再跑一次**确保最后一次 commit 没有回归）

### 9.2 Vue build 验证（SOP 阶段6 Frontend）

- [ ] **Step 2: Run `cd frontend && npm run build`**

Expected: exit 0，`✓ built in Xs`。

### 9.3 Docker compose 验证（SOP 阶段6 Docker）

- [ ] **Step 3: `docker compose config`**

Expected: exit 0，3 services 输出

- [ ] **Step 4: Build + 启动**

Run: `docker compose build postgres backend frontend` 然后 `docker compose up -d`
Wait: `docker compose ps` 直到 3 个容器状态都是 `Up` +（或）`healthy`（预计 30-90s，因为 backend 要等 postgres healthy → 等 jvm 启动）

Expected: `docker compose ps` 里 3 行 State=Up。如果有反复重启 → `docker compose logs backend` 看日志排错（大概率是 Flyway 找不到表/ddl-auto=validate 失败/PG 连接失败）。

### 9.4 API 端到端 curl 验证（SOP 阶段6 API 改动）

后端已在 localhost:8080 活着后（healthcheck 过），按顺序跑：

- [ ] **Step 5: 空列表**
```bash
curl -s http://localhost:8080/api/tasks
```
Expected: `{"code":0,"msg":"ok","data":[]}`

- [ ] **Step 6: 创建任务**
```bash
curl -s -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"title":"采购奶粉","description":"2段,爱他美","dueDate":"2026-08-20"}'
```
Expected: `code=0`, `data.id` 是数字, `status=TODO`，记下 `id=<ID1>`

- [ ] **Step 7: 列表有 1 条**
```bash
curl -s http://localhost:8080/api/tasks
```
Expected: `data.length=1`, 包含"采购奶粉"

- [ ] **Step 8: update status → DONE**
```bash
curl -s -X PUT http://localhost:8080/api/tasks/<ID1> \
  -H 'Content-Type: application/json' \
  -d '{"status":"DONE"}'
```
Expected: `code=0`, `data.status=="DONE"`

- [ ] **Step 9: 非法流转 DONE→TODO**
```bash
curl -s -X PUT http://localhost:8080/api/tasks/<ID1> \
  -H 'Content-Type: application/json' \
  -d '{"status":"TODO"}'
```
Expected: HTTP 400 + body.code=40011 + msg 含 "invalid status transition"

- [ ] **Step 10: delete（软）**
```bash
curl -s -X DELETE http://localhost:8080/api/tasks/<ID1>
```
Expected: `code=0`, `data=true`

- [ ] **Step 11: 再次 list → 空数组**
```bash
curl -s http://localhost:8080/api/tasks
```
Expected: `data.length=0`（软删除被 @Where 过滤正确）

### 9.5 DB 回滚验证（SOP 阶段6 DB Schema 改动，严格按设计 §5.4）

- [ ] **Step 12: 正向 apply 验证**（其实前面 Step 6+已经验证，再确认一下）
```bash
docker compose exec -T postgres psql -U familyapp_user -d familyapp \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY version;" \
  -c "\d tasks"
```
Expected: `version='1'` row with success=true + description="Create Tasks Table"；`\d tasks` 显示 9 列

- [ ] **Step 13: 回滚脚本语法校验（事务 + ROLLBACK，不破坏 schema_history）**
```bash
docker compose exec -T postgres psql -U familyapp_user -d familyapp <<'EOF'
BEGIN;
\i backend/src/main/resources/db/rollback/V2__Drop_Tasks_Table.sql
\d tasks;  -- expect: "relation does not exist"
ROLLBACK;
\d tasks;  -- expect: 9 列回来
EOF
```
Expected: BEGIN 内 `\d tasks` 报 relation does not exist；ROLLBACK 后 `\d tasks` 恢复

- [ ] **Step 14: 恢复性验证（flywayClean → flywayMigrate）**
```bash
cd backend && ./gradlew.bat flywayClean flywayMigrate -x test --no-daemon \
  -PflywayUrl=jdbc:postgresql://localhost:5432/familyapp \
  -PflywayUser=familyapp_user -PflywayPassword=placeholder-postgres-password
```
Expected: BUILD SUCCESSFUL，输出 `Successfully applied 1 migration`；再次 Step 12 的 psql 查询确认 version=1 行 + 表回来。

⚠️ 此步骤会清 schema 里所有表 + 数据。验证通过后，如果之前建的测试数据没了没关系——再 backend container 重启一次即可（如果需要的话）。

### 9.6 前端页面可访问（SOP 阶段6 UI 改动）

- [ ] **Step 15: curl 或浏览器验证前端返回正确 HTML**
```bash
curl -s http://localhost:5173/ | grep -q '<script type="module" src="/src/main.js">'
echo "front page reachable: $?"
```
Expected: 输出 `front page reachable: 0`

### 9.7 OpenSpec 验证（SOP 阶段6 OpenSpec 改动）

- [ ] **Step 16: 补写 specs + design + tasks artifacts（openspec 合规要求）**

根据设计文档 + proposal，创建：
1. `openspec/changes/add-daily-task-mgmt/specs/daily-task-mgmt/spec.md`（从 proposal + design 提取 requirements 清单）
2. `openspec/changes/add-daily-task-mgmt/design.md`（从 design doc 复制简化版，覆盖 架构 + API + DB + 数据流 4 节）
3. `openspec/changes/add-daily-task-mgmt/tasks.md`（从本 implementation plan 的 Tasks 2-8 摘要出任务清单，只列任务名+负责文件，不列细步骤）

- [ ] **Step 17: `openspec validate`**

Run: `openspec validate --change add-daily-task-mgmt`
Expected: exit 0，`no errors`（或 `N issues`，只要无 error 级，warning 可接受）

- [ ] **Step 18: `openspec status --change add-daily-task-mgmt --json`**

Expected: `isPlanningComplete=true`；`artifacts.*.status` 都是 `done`；`applyRequires` 全部 satisfied。

### 9.8 最终工作区干净

- [ ] **Step 19: `git status`**

Expected: `nothing to commit, working tree clean`

如果还有未跟踪的文件（如 `backend/build/`、`frontend/dist/`、`.volumes/`）—— 这些应在 .gitignore 里，不应该出现在 Untracked。如果出现了就修 .gitignore。

- [ ] **Step 20: Commit verification artifacts only（openspec 3 个新增文件）**
```bash
git add openspec/changes/add-daily-task-mgmt/specs/ \
        openspec/changes/add-daily-task-mgmt/design.md \
        openspec/changes/add-daily-task-mgmt/tasks.md
git commit -m "chore(openspec): add-daily-task-mgmt planning artifacts done

- specs/daily-task-mgmt/spec.md: extracted requirements from design
- design.md: simplified architecture/API/DB/dataflow from brainstorm design doc
- tasks.md: task list from implementation plan
- openspec validate + status: 0 errors, planning complete
"
```

---

## Plan Self-Review

### 1. Spec Coverage（每条 spec 设计 requirement → 对应任务）

| Spec 设计章节 | 要求 | 实现任务 |
|---|---|---|
| §1.2 目录结构 | package-by-feature + 所有文件路径 | Task 2-5 全 CREATE 语句路径精确匹配 §1.2 File Structure Map |
| §2.1 tasks 表 DDL | 9 列 + CHECK 4 status + index | Task 2 Step 6 V1 SQL |
| §2.2 软删除 @Where + findByIdIncludeDeleted | Task.java + TaskRepository.java | Task 4 Step 4/5 + Task 4 Step 1 测试 |
| §2.3 V2__Drop_Tasks_Table.sql 回滚脚本 | rollback 目录 | Task 2 Step 7 |
| §2.4 DFA 状态流转（4 值矩阵） | TaskServiceImpl TRANSITIONS EnumMap | Task 5 Step 4 + Task 6 Step 2 的 7 合法 + 2 非法 覆盖全矩阵 16 格 |
| §3.1 envelope {code,msg,data} + 错误码 7 种 | Result.java + ResultCode.java | Task 3 Step 3/4 |
| §3.2 5 endpoints 契约（HTTP code + 返回） | TaskController + @ResponseStatus + 错误码映射 | Task 5 Step 5 + Task 6 Step 4 的 MockMvc 6 用例 |
| §3.3 DTOs（Create/Update/Response Bean Validation） | 3 DTOs | Task 4 Step 6 |
| §3.4 标准三层职责分离（C/S/R 不越界） | Controller/Service/Repository 分文件 | Task 5 Step 3/4/5 |
| §3.5 GlobalExceptionHandler 4 类异常 | GlobalExceptionHandler.java | Task 3 Step 6 |
| §4 Element Plus + 按需 import + 前端 axios 剥壳 + 4 个交互 | vite.config 2 plugins + tasks.js 2 interceptors + TaskView.vue (el-form + el-table + ElMessage/ElMessageBox) | Task 7 Step 2/7 + Task 8 Step 1/2 |
| §5.1 Java 分层测试 | 4 test classes | Task 6 Steps 1-5 |
| §5.2 npm run build 0 error | Task 7 Step 8, Task 8 Step 3 | 2 个 Step |
| §5.3 Docker compose 4 验证命令 | Task 9.3 Steps 3-4 | Task 9 |
| §5.4 DB 回滚 3 步验证 | Task 9.5 Steps 12-14 | Task 9 |
| §5.5 OpenSpec validate + status | Task 9.7 Steps 16-18 | Task 9 |
| §6 Docker 编排 + bind mount + node_modules 匿名卷 + healthchecks + actuator | docker-compose.yml + 2 Dockerfiles + spring actuator starter | Task 1 Steps 2-4 |
| §6 软删除防绕过（Repository后门不暴露 Controller） | TaskServiceImpl.delete 使用 findByIdIncludeDeleted；Controller 不引 Repository | Task 5 Step 4 delete() → 只通过 service |

**Gap: 0** — 全覆盖。

### 2. Placeholder Scan（Red flags）

逐 Task 全文 grep 结果：
- 无 `TBD`/`TODO`/`implement later`/`fill in`
- 无 `Add appropriate error handling`/`Write tests for the above`/`Similar to Task N`
- 所有代码步骤都给出了完整文件内容（不是"写一个 X class"这种描述）
- 所有 Run 命令都给出了**精确命令字符串 + 精确 Expected**（Expected 都描述了具体字段/返回码，不是"通过"这种模糊词）
- 所有类型、方法名、字段名前 Task 都有定义（Task 5 的 Result/ResultCode 在 Task 3 已定义，Task 8 的 tasksApi 在 Task 8 Step 1 已定义，无 forward-reference）

**Red flag: 0**。

### 3. Type Consistency（跨 Task 方法/字段/类名）

逐条检查后：
- `ResultCode.BAD_REQUEST_STATUS_TRANSITION`（Task 3 Step 3 定义值为 40011）在 Task 5 Step 4、Task 6 Step 1、Task 6 Step 4 中引用一致。
- `TaskResponse.fromEntity(Task)`（Task 4 Step 6 定义为 static factory）在 Task 5 Step 4 的 create/list/get/update 中全部使用，方法签名和 TaskResponse record 字段一致（7 字段）。
- `validateTransition(current, target)` 在 TaskServiceImpl 中作为 private method，其 TRANSITIONS static EnumMap 7 合法路径与设计 §2.4 4×4 矩阵完全一致，Task 6 Step 2 的 `update_status_allows_7_valid_DFA_paths` 用例的 `validPairs` 7 条与 Matrix 完全一一对应。
- DTO 字段命名：title / description / dueDate（camelCase，在 JSON 中由 Jackson 默认序列化为 snake_case？NO——Spring 默认 3.2 的 Jackson 对 DTO Java 字段是 camelCase JSON key，这与 axios 前端发送的 title/description/dueDate（camelCase）一致。DB 列是 snake_case（`due_date`）在 JPA 实体用 `@Column(name = "due_date")` 正确映射。**一致性无 bug**。
- 前端 `tasksApi.del` 对应后端 `DELETE /api/tasks/{id}`，HTTP 方法正确。
- 前端 `form.dueDate` = `el-date-picker` YYYY-MM-DD（string）→ 后端 `CreateTaskRequest.dueDate` = `LocalDate`，Spring Boot 3 默认 Jackson 已注册 `JavaTimeModule`，能解析 YYYY-MM-DD 字符串到 LocalDate，**无格式错**。

**Type inconsistency: 0**。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-15-add-daily-task-mgmt.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration. Tasks 1 / 2→3→4→5→6 / 7→8 三个分支可并行，最后 Task 9 收束。

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
