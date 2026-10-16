## Context

See `proposal.md — Why` for motivation. The change maps to one new capability: `daily-task-mgmt` (see proposal `Capabilities`). Implementation code, 22 commits (HEAD `41b51e4`), and test results (20/20 passing JUnit XML) already exist in the working tree at the time of writing; this design document retroactively records the technical decisions that produced them so OpenSpec artifact completeness is achieved before archiving.

Stack constraints that drive every decision below: Java 17 + Gradle 8.8 + Spring Boot 3.2 + JPA/Hibernate + PostgreSQL 16 via docker-compose; Flyway for migrations; H2 in-memory for unit tests; Vue 3 + Vite + Element Plus + axios on the frontend; 3-container dev topology (postgres + backend + frontend) defined in `docker-compose.yml`.

## Goals / Non-Goals

**Goals:**
- Deliver one end-to-end vertical slice covering persistence → REST API → SPA page → container orchestration, so subsequent changes can copy the established package layout, test layering, Result envelope, migration strategy, and docker-compose patterns.
- Encode the task workflow as a deterministic finite automaton (DFA) in the service layer so invalid transitions never reach the database.
- Keep deletes recoverable by using soft-delete semantics at the repository layer.
- Guarantee a single uniform HTTP response envelope `Result<T> = {code, msg, data}` across success paths and all handled exception paths via a global controller advice.

**Non-Goals:**
- No authentication / family membership / task assignment (depends on a later account-system change; explicitly excluded from proposal Non-goals).
- No task priorities, labels, categories, or recurrence rules (scoped out in proposal Non-goals; dedicated change later).
- No Redis usage inside this change. Redis is listed in the project stack for future caching / queue scenarios; the Task CRUD path works without it.
- No frontend unit tests or e2e in this change. SPA build-success + manual UI sanity check suffice for the scaffold baseline.

## Decisions

### D1 — Backend package layout and layering
**Decision**: `com.familyapp` root package with four parallel modules:
- `common/` — `Result<T>`, `ResultCode`, `ApiException`, `GlobalExceptionHandler`
- `config/` — `WebConfig` (CORS + actuator exposure)
- `task/` — `Task` (JPA entity), `TaskStatus` (enum), `TaskRepository` (Spring Data JPA), `TaskService`/`TaskServiceImpl`, `TaskController`, sub-package `task/dto/` for `CreateTaskRequest`, `UpdateTaskRequest`, `TaskResponse`
- `FamilyAppApplication` main class at the package root to let `@SpringBootApplication` scan all sub-packages.

Why this over alternatives:
- Single-module monorepo avoids premature multi-project Gradle complexity for a CRUD-sized change. Separate Gradle subprojects can be introduced when the Python AI module or a second bounded context arrives.
- DTOs kept inside `task/dto/` (not a top-level `dto/`) because each bounded context owns its request/response shapes and there is zero re-use across contexts at this stage.

### D2 — DFA encoded in service layer, enum-backed
**Decision**: `TaskStatus` enum = `TODO, IN_PROGRESS, DONE, CANCELLED`. `TaskServiceImpl.update()` extracts the current record, and if `request.status != null`, walks a hand-coded `Map<TaskStatus, Set<TaskStatus>>` legal transitions table (TODO ↔ IN_PROGRESS → DONE → CANCELLED absorbs; CANCELLED has zero outgoing edges). Any violation throws `ApiException(ResultCode.STATUS_TRANSITION_INVALID, 400)`.

Why rules-table over state-machine library:
- Four states, fewer than ten legal transitions; a SparseSet is readable in one screen and unit-testable in parameterized form without pulling a library dependency.
- Keeps transition logic side-effect free and pure (no DB access), enabling `TaskServiceTest.update_status_allows_7_valid_DFA_paths()` to cover every edge.

### D3 — Soft delete via entity boolean field vs repository @Modifying query
**Decision**: `Task` entity carries a `boolean deleted = false` column (default `false`). `TaskRepository` extends `JpaRepository<Task, Long>` and adds:
- `Optional<Task> findByIdAndDeletedFalse(Long id)` — used by GET-by-id and UPDATE flows
- `List<Task> findByDeletedFalseOrderByCreatedAtDesc()` — LIST flow
- `@Modifying @Query("UPDATE Task t SET t.deleted = true WHERE t.id = :id AND t.deleted = false") int softDeleteById(@Param("id") Long id)` — service layer calls this; if returned rows == 0 the service throws 404.

Why not `@SQLDelete` / Hibernate `@Where`:
- Hibernate's `@SQLDelete` + `@Where(clause = "deleted=false")` auto-filter would silently change `findAll()` behavior, which is convenient until a future admin/audit view legitimately needs to list soft-deleted rows. Explicit repository methods make the "include-deleted vs exclude-deleted" choice visible at every call site.

### D4 — Result<T> envelope + GlobalExceptionHandler
**Decision**: All controllers return `ResponseEntity<Result<X>>` through the static helpers `Result.success(data)` / `Result.fail(code, msg)`. `GlobalExceptionHandler` catches `ApiException` (maps to envelope with ResultCode) + `MethodArgumentNotValidException` (stacks validation messages into envelope with 400) + generic `Exception` (logs and returns 500 envelope). `ResultCode` is a small enum of `SUCCESS(0)`, `BAD_REQUEST(40000)`, `NOT_FOUND(40400)`, `STATUS_TRANSITION_INVALID(40010)` so the envelope `code` is numeric but still callable via name in Java code.

Why not RFC 7807 `ProblemDetail`:
- Proposal explicitly called for a unified `{code, msg, data}` envelope; frontend axios interceptors expect exactly these three keys. `ProblemDetail` introduces `type/title/status/...` keys that do not match the contract. Envelope is the controlling contract.

### D5 — DB migration strategy: Flyway + validate vs auto-ddl
**Decision**: `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` in every non-test environment + `SPRING_FLYWAY_ENABLED=true`. Migration `V1__create_tasks.sql` creates the `tasks` table with `id bigserial PK`, `title varchar(255) NOT NULL`, `description text`, `due_date date`, `status varchar(20) NOT NULL`, `deleted boolean NOT NULL DEFAULT false`, `created_at timestamptz NOT NULL DEFAULT now()`, `updated_at timestamptz NOT NULL DEFAULT now()`. JPA entity `@Column` names align to snake_case via Hibernate's default `SpringPhysicalNamingStrategy`.

Why `validate` over `create-drop`:
- `create-drop` loses data on container restart and masks DDL/JPA mismatch. `validate` fails fast on boot if entity drifts from schema.

### D6 — Docker topology: 3 services, depends_on + healthchecks
**Decision**: `docker-compose.yml` defines:
- `postgres:16-alpine` — `pg_isready` healthcheck, data volume under `./.volumes/postgres/`
- `backend` (Dockerfile at `backend/Dockerfile` 2-stage: Gradle build → JRE 17 slim) — depends_on postgres healthy; env vars wire datasource; actuator `/actuator/health` exposed
- `frontend` (Dockerfile at `frontend/Dockerfile`: node → npm ci → npm run dev) — depends_on backend healthy; `VITE_PROXY_TARGET=http://backend:8080`; volume mount for HMR except `node_modules`; `python-ai` service left commented out with `profiles: ["ai"]` per proposal.

Why healthchecks over bare depends_on:
- `depends_on: service_started` alone means backend JVM starts before postgres accepts connections. `condition: service_healthy` is compatible with docker-compose v2.20+ and removes the need for a custom `wait-for-it.sh` wrapper.

### D7 — Frontend architecture: Vite + Element Plus + axios api client module
**Decision**:
- `vite.config.js` declares `server.proxy['/api'] = { target, changeOrigin: true }` (target defaults to env `VITE_PROXY_TARGET` else `http://localhost:8080`).
- `src/api/tasks.js` exports `listTasks()`, `getTask(id)`, `createTask(payload)`, `updateTask(id, payload)`, `deleteTask(id)` — all `axios({ method, url: '/api/tasks/...', ... })` returning `response.data.data` (strip outer envelope).
- `src/views/TaskView.vue` uses `<el-table>` + `<el-form>` on a single page; create/delete calls trigger a `loadTasks()` re-fetch rather than optimistic DOM manipulation, so list state is always derived from backend truth.

Why Element Plus over naive HTML + CSS:
- Proposal Non-goals explicitly say "本次只求链路打通，不做设计/响应式". Element Plus ships accessible form validation + table out of the box, freeing effort to focus on axios wiring + proxy correctness. Why not Pinia/Vuex: only one route; single `ref<Task[]>` is enough state without a store.

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| DFA encoding and enum ordering drift apart between service and controller tests | 20 JUnit tests cover every legal + illegal transition (see `TaskServiceTest` 10 cases). CI must gate on `./gradlew test` green. |
| Soft-delete forgotten in new repository methods (e.g. accidental `findById` instead of `findByIdAndDeletedFalse`) | Repo naming convention *always* encodes the deleted-filter choice in the method name. Code review + grep for `findById\(` catches regressions. |
| Envelope shape drifts when controller returns raw objects instead of `Result.*(...)` | `GlobalExceptionHandler` tests + `ResultTest` keep envelope fixed. Future lint rule or ArchUnit test can enforce the return type globally. |
| Vite proxy misrouting `/api` requests to frontend dev server instead of backend in compose | Commit `fa95e06` added fallback + proxy verification. Manual validate in `docker compose up --build`: curl `http://localhost:8080/api/tasks` directly first, then `http://localhost:5173/api/tasks` second. Both must return the envelope. |
| Hibernate PostgreSQLDialect deprecation warning spams logs | Spring boot 3.2 auto-selects dialect. Remove explicit `spring.jpa.properties.hibernate.dialect` property (already noted in test log WARN line). |
| Flyway migration V1 never tested against real Postgres (only H2 in unit tests) | Next session: after docker compose up, confirm `flyway_schema_history` table has 1 row with version=1 success; or add a testcontainer-based integration test in follow-up change. |
| Frontend no unit tests — UI regressions silent until someone clicks TaskView | Accept risk for the scaffold-sized change; add vitest + @vue/test-utils in change #2 or #3, or task #6 (future) immediately below. |

## Migration Plan

This is a greenfield change (no pre-existing tasks table or frontend page). Migration steps:
1. `docker compose up --build postgres` → waits for healthy.
2. `docker compose up backend` → Flyway V1__create_tasks.sql auto-applies against empty `familyapp` DB; Hibernate `ddl-auto=validate` confirms entity matches.
3. `docker compose up frontend` → Vite proxy `/api` → backend health confirmed.
4. Developer sanity: `POST` a task via curl, `GET` list confirms present, `DELETE` hides it, second `DELETE` returns 404 per Scenario.

Rollback: this change has zero forward-compat constraints because it is the first table. If needed:
- DB: drop the tasks table (`DROP TABLE IF EXISTS tasks;`) and delete flyway history row for V1.
- Containers: `docker compose down -v` wipes the `.volumes/` mount entirely.
- Code: git revert range `d3ea328..41b51e4`.

## Open Questions

None. The four unknowns that typically surface at design time are already resolved:
- Task DFA shape → encoded in service tests, frozen.
- Envelope status codes → numeric codes assigned in `ResultCode` enum.
- Soft-delete visibility policy → find/list/get/delete-by-id behaviors locked in scenarios; future "trash/bin" view can add a repository method without re-opening this design.
- Frontend state strategy for TaskView → explicit server-truth refetch over optimistic store; good enough until a second page arrives.
