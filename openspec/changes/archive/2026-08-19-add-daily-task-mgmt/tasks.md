## 1. Infra scaffolding

- [x] 1.1 Add `.worktrees` to `.gitignore` to support isolated feature worktrees (commit `d3b126c`)
- [x] 1.2 Define docker-compose 3-service topology (postgres/backend/frontend), Dockerfile skeletons, and dockerignore rules (commit `d668cf5`)
- [x] 1.3 Extend `.gitignore` to exclude `frontend/node_modules/` so npm install output never enters version control (commit `9c89b17`)
- [x] 1.4 Add local-only dev entrypoints: `dev.ps1` (Windows) + `scripts/dev.sh` (WSL/Linux) and `.env.example` env template

## 2. Backend common layer and base configuration

- [x] 2.1 Initialize Gradle Spring Boot project in `backend/`: `build.gradle` (Spring Boot 3.2 + JPA + PostgreSQL driver + Flyway + H2 test + validation + actuator), `settings.gradle`, Gradle wrapper `gradlew`/`gradlew.bat`, wrapper jar (commit `745a78e`)
- [x] 2.2 Implement unified `Result<T>` envelope (`{code, msg, data}`), `ResultCode` numeric enum, business `ApiException`, and `GlobalExceptionHandler` mapping ApiException / MethodArgumentNotValidException / generic Exception to envelope status codes (commit `83ea319`)
- [x] 2.3 Add `WebConfig` for CORS allowed origins and actuator endpoint exposure; add `FamilyAppApplication` main class at `com.familyapp` package root so component scan covers common/config/task

## 3. Backend task domain layer + Flyway migration V1

- [x] 3.1 Write Flyway migration `V1__create_tasks.sql`: `tasks` table with `id bigserial PK`, `title varchar(255) NOT NULL`, `description text`, `due_date date`, `status varchar(20) NOT NULL`, `deleted boolean NOT NULL DEFAULT false`, `created_at/updated_at timestamptz DEFAULT now()`; include corresponding `U1__drop_tasks.sql` rollback path or equivalent manual drop instruction (commit `745a78e`)
- [x] 3.2 Create JPA entity `Task` with snake_case column names matching migration, Hibernate naming strategy default, and `@Enumerated(EnumType.STRING)` for `TaskStatus` (commit `31424ef`)
- [x] 3.3 Create `TaskStatus` enum with exactly four values: `TODO`, `IN_PROGRESS`, `DONE`, `CANCELLED` (commit `31424ef`)
- [x] 3.4 Create Spring Data JPA `TaskRepository` with explicit soft-delete filtering methods: `findByIdAndDeletedFalse(Long)`, `findByDeletedFalseOrderByCreatedAtDesc()`, and `@Modifying @Query int softDeleteById(Long id)` — avoid Hibernate @SQLDelete / @Where to keep call sites auditable (commit `31424ef`)
- [x] 3.5 Create request/response DTOs in `task/dto/`: `CreateTaskRequest` (@NotBlank title, optional description/dueDate/status with enum validator), `UpdateTaskRequest` (all fields optional), `TaskResponse` (all persisted fields including createdAt/updatedAt) (commit `31424ef`)

## 4. Backend task service layer + controller REST endpoints

- [x] 4.1 Implement `TaskServiceImpl` with: DFA transition table encoded as `Map<TaskStatus, Set<TaskStatus>>`; CREATE rejects CANCELLED initial status and defaults null status to TODO; UPDATE accepts partial fields and enforces DFA (rejects DONE→TODO and CANCELLED→any); DELETE uses soft-delete query and returns 404 on double-delete or unknown id (commit `4931a0b`)
- [x] 4.2 Implement `TaskController` exposing 5 endpoints: `POST /api/tasks` → 201 envelope, `GET /api/tasks` → list, `GET /api/tasks/{id}` → detail or 404, `PUT /api/tasks/{id}` → partial update or transition error, `DELETE /api/tasks/{id}` → 2xx or 404. All endpoints return `ResponseEntity<Result<T>>` (commit `31424ef` integrates controller)
- [x] 4.3 Configure application properties: `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` in docker env; `SPRING_FLYWAY_ENABLED=true`; PostgreSQL datasource; test profile overrides datasource to H2 in-memory and disables Flyway (uses JPA ddl-auto=create-drop) for fast unit tests

## 5. Backend layered test suite

- [x] 5.1 Write context smoke test `FamilyAppApplicationTest#contextLoads()`: validates Spring context wires without real Postgres (uses H2 test profile, committed as part of aeccafa)
- [x] 5.2 Write Result envelope unit test: verify `Result.success()` produces code=0,msg=success and `Result.fail()` propagates code and message correctly
- [x] 5.3 Write TaskService layer unit tests (10 cases, all green per `backend/build/test-results/test/TEST-com.familyapp.task.TaskServiceTest.xml`): DFA all 7 legal transitions + DONE→TODO rejection + CANCELLED→ANY rejection + double delete 404 + soft delete marker + create defaults TODO + create rejects CANCELLED + list maps correctly + partial field update + get returns or throws (commit aeccafa)
- [x] 5.4 Write TaskRepository layer tests (H2-backed): verify findByIdAndDeletedFalse excludes soft-deleted, findByDeletedFalseOrderByCreatedAtDesc returns newest first, softDeleteById returns 0 rows for unknown id and 1 row for first delete only
- [x] 5.5 Write TaskController MockMvc tests (6+): POST happy path and title-missing validation → 400; GET list empty/non-empty; GET-by-id → 404; PUT status transition violation → 400; DELETE and then second DELETE → 404; all assertions validate envelope fields alongside HTTP status
- [x] 5.6 Run `./gradlew test` locally and commit test-results XML as evidence (achieved 20/20 passing, failures=0, errors=0)

## 6. Frontend scaffold, API client, and TaskView page

- [x] 6.1 Initialize Vue 3 + Vite project in `frontend/`: add Element Plus, axios, unplugin-auto-import; write `vite.config.js` with `/api` proxy to env `VITE_PROXY_TARGET` fallback `http://localhost:8080`; write `package.json` scripts (dev/build/preview) (commit `0668ee7`)
- [x] 6.2 Write `src/api/tasks.js` axios client module with five callables (`listTasks`, `getTask`, `createTask`, `updateTask`, `deleteTask`) that destructure `response.data.data` to strip the outer envelope (commit `9b6c65b`)
- [x] 6.3 Write `src/views/TaskView.vue`: `el-table` rendering backend list, `el-form` for create (required title validator), per-row delete button, all mutations followed by re-fetch `loadTasks()` so UI state is always server-truth driven; empty state message when backend list is length zero (commit `9b6c65b`)
- [x] 6.4 Wire `src/main.js` to mount Element Plus globally; update `src/App.vue` to render TaskView as the default route without adding vue-router for now; run `npm run build` and commit success evidence in `frontend/dist/`
- [x] 6.5 Write frontend multi-stage `Dockerfile`: node base → `npm ci` against lockfile → `npm run dev`; add `.dockerignore` excluding `node_modules`, `dist`, and OS junk

## 7. Integration fixes and final validation

- [x] 7.1 Fix vite proxy fallback so requests to `/api` that 404 on the frontend dev server correctly proxy to the backend instead of serving the SPA's index.html (part of commit `fa95e06`)
- [x] 7.2 Add rollback cleanup for any dangling container state in compose stop flows and curl-based healthcheck callable from inside the backend container after start (part of commit `fa95e06`)
- [x] 7.3 Add backend `.dockerignore` that excludes `.gradle`, `build`, and local sources so docker build only copies what the 2-stage Gradle build needs
- [x] 7.4 Validate docker-compose file manually: `docker compose config` parses without error, 3 services defined (postgres, backend, frontend), depends_on healthy chains respected, python-ai service remains commented with `profiles:["ai"]` marker
- [x] 7.5 (Recommended manual step, done via prior session) Start docker-compose stack end-to-end: POST a task via curl → GET it → DELETE → verify 404 on double DELETE and on refetch; open `http://localhost:5173` in browser and confirm TaskView loop performs equivalently
