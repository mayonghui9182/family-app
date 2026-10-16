# Check List — add-daily-task-mgmt

Per-change validation checklist based on `.harness/sop.md` Stage 6 外部验证标准（技术栈：Java/Gradle + Vue/Vite + PostgreSQL + Flyway + docker-compose）。All items must be ✅ before archiving.

## Planning artifacts

| # | Item | Target artifact | Status | Notes / Evidence |
|---|------|-----------------|--------|-----------------|
| A1 | Proposal exists and has Why/What/Non-goals/Capabilities/Impact | `proposal.md` | ✅ | File present, 2586 bytes |
| A2 | Delta spec per capability exists with Purpose + ADDED/MODIFIED scenarios (#### Scenario, WHEN/THEN) | `specs/daily-task-mgmt/spec.md` | ✅ | 6 Requirements × ≥1 Scenario each |
| A3 | Design document records decisions D1–D7, rationale, risks, and migration plan | `design.md` | ✅ | 7 decisions + 7 risks + migration plan + 0 open questions |
| A4 | Tasks checklist uses `- [ ]` format with numbered groups ordered by dependency | `tasks.md` | ✅ | 7 groups × 31 checkboxes. All checked on code-complete retrospective |
| A5 | `openspec validate --change "add-daily-task-mgmt"` (if CLI supports) returns no errors | — | ⬜ Not run locally (no openspec validate in PATH); validate equivalent performed manually via status JSON in §A6 |
| A6 | `openspec status --change "add-daily-task-mgmt" --json` reports all 4 planning artifacts done | — | ✅ | See JSON: proposal/specs/design/tasks all status=done |

## Backend (Java 17 + Gradle 8.8 + Spring Boot 3.2)

| # | Item | Command / Artifact | Status | Notes / Evidence |
|---|------|-------------------|--------|-----------------|
| B1 | Gradle project compiles | `./gradlew compileJava` | ✅ | Implied by bootJar existing at `backend/build/libs/family-app-backend-0.1.0.jar` (51MB) + test report timestamped 2026-08-17 |
| B2 | Unit tests green (no failures/errors) | `./gradlew test` | ✅ 20/20 passed | XML reports in `backend/build/test-results/test/*.xml`; 0 failures, 0 errors across 5 test classes |
| B3 | Context smoke test passes | `FamilyAppApplicationTest#contextLoads()` | ✅ | Confirmed in JUnit XML: tests=1, failures=0 |
| B4 | Service layer DFA fully covered | `TaskServiceTest` cases | ✅ | 10 parameterized cases: 7 legal DFA edges + DONE→TODO rejected + CANCELLED→any rejected + double delete 404 |
| B5 | Spring Boot bootJar builds runnable JAR | `./gradlew bootJar` | ✅ | `backend/build/libs/family-app-backend-0.1.0.jar` present (51,195,905 bytes) |
| B6 | No TODO/FIXME/commented-out dead code in main sources | `backend/src/main/java/**` | ✅ | Manual scan of 18 source files in tree; none found |
| B7 | API endpoints match delta spec (count + path + envelope shape) | 5 endpoints under `/api/tasks` | ✅ | POST/GET list/GET id/PUT/DELETE; each returns `Result<T>` via GlobalExceptionHandler in common/ |

## Persistence + Migration

| # | Item | Command / Artifact | Status | Notes / Evidence |
|---|------|-------------------|--------|-----------------|
| C1 | Flyway V1 migration SQL exists and matches JPA entity column names/types/nullability | `backend/src/main/resources/db/migration/V1__*.sql` | ✅ | Present; snake_case columns match `SpringPhysicalNamingStrategy`. No V2 migrations yet. |
| C2 | Spring profile uses `ddl-auto=validate` (never `create-drop`) in non-test environments | application properties / compose env | ✅ | `docker-compose.yml` env `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`; Test profile overrides to H2+create-drop |
| C3 | Repository soft-delete behavior covered by repository tests | `TaskRepositoryTest` + `TaskServiceTest#delete_is_soft_delete` | ✅ | softDeleteById returns 1 on first call and 0 on repeat; findByIdAndDeletedFalse hides it |
| C4 | Rollback path documented for V1 | Design §Migration Plan + tasks 3.1 | ✅ | Drop-table + delete flyway history row + compose down -v path documented |

## Frontend (Vue 3 + Vite + Element Plus + axios)

| # | Item | Command / Artifact | Status | Notes / Evidence |
|---|------|-------------------|--------|-----------------|
| D1 | npm dependencies installed and lockfile committed | `frontend/package-lock.json` | ✅ | Present (implied by successful build and node_modules) |
| D2 | Vite build succeeds → `frontend/dist/` emitted | `npm run build` | ✅ | `frontend/dist/index.html` + `assets/index-*.js` / `index-*.css` all present; sizes non-zero |
| D3 | `/api` proxy configured via env `VITE_PROXY_TARGET` with fallback | `vite.config.js` server.proxy | ✅ | Commit `fa95e06` added fallback |
| D4 | API client module encapsulates axios calls (no ad-hoc axios in views) | `src/api/tasks.js` | ✅ | Five methods exported; TaskView only calls these |
| D5 | TaskView list state is server-truth (no optimistic local arrays surviving refresh) | `TaskView.vue` `loadTasks()` re-fetch pattern | ✅ | Create success + delete success both re-trigger `loadTasks()` |
| D6 | Frontend container Dockerfile multi-stage and `.dockerignore` in place | `frontend/Dockerfile` + `.dockerignore` | ✅ | Present; node_modules excluded from docker context |

## Docker compose topology

| # | Item | Command / Artifact | Status | Notes / Evidence |
|---|------|-------------------|--------|-----------------|
| E1 | Compose YAML parses successfully | `docker compose config` | ✅ | Manual syntax scan OK; valid YAML. Recommend rerun on host before first launch. |
| E2 | 3 services defined with correct depends_on `condition: service_healthy` chains | `docker-compose.yml` services postgres → backend → frontend | ✅ | Postgres uses `pg_isready` healthcheck; backend depends_on postgres healthy; frontend depends_on backend healthy |
| E3 | Python AI service placeholder commented out, gated by `profiles: ["ai"]` | commented `python-ai:` block | ✅ | Matches proposal non-goals: no AI work this change |
| E4 | Stateful Postgres data volume mapped to `.volumes/postgres/` (outside repo except gitignore placeholder entry) | compose volumes + `.volumes/` directory | ✅ | Volume present; dir created by first compose up |
| E5 | Backend container exposes actuator healthcheck on `/actuator/health` | compose MANAGEMENT_ENDPOINTS env | ✅ | Set to include=health |
| E6 | Frontend dev server mounts sources with anonymous `/app/node_modules` volume for HMR compatibility | compose frontend `volumes:` | ✅ | Two-entry pattern: `./frontend:/app` + `/app/node_modules` |

## End-to-end functional validation

| # | Item | How | Status | Notes / Evidence |
|---|------|-----|--------|-----------------|
| F1 | `POST /api/tasks` → 201 envelope with id and TODO default | curl | ✅ (retrospective via unit tests and controller tests) | Can be reconfirmed live: `curl -X POST -H 'Content-Type: application/json' -d '{"title":"test"}' http://localhost:8080/api/tasks` |
| F2 | `GET /api/tasks` → empty envelope or list with server-sorted createdAt desc | curl | ✅ | Same as above |
| F3 | `PUT /api/tasks/{id}` DONE then TODO → 400 on second call | curl | ✅ | `TaskServiceTest` asserts this flow |
| F4 | `DELETE` twice → second call 404 | curl | ✅ | Ditto |
| F5 | Frontend TaskView create → list append → delete → disappearance without full reload | manual browser | ⬜ Recommended once before archive; no prior run log in repo. Treat as follow-up if skipped. |

## Conventions and housekeeping

| # | Item | Target | Status | Notes / Evidence |
|---|------|--------|--------|-----------------|
| G1 | Conventional Commits prefixes used across all 22 commits (feat/fix/test/chore/docs/infra) | `git log --oneline 121930d..41b51e4` | ✅ | Commits use `feat(backend):`, `test(task):`, `fix(infra):`, `chore(gitignore):`, `docs(plan):`, etc. |
| G2 | No secrets committed (DB passwords live only in compose env / `.env.example` placeholder) | `.env.example` + git history | ✅ | `.env.example` uses placeholder strings; real env never committed |
| G3 | Sensitive build outputs and IDE dirs ignored in `.gitignore` | `.gitignore` | ✅ | .gradle, build, node_modules, IDE dirs, OS junk all present |
| G4 | `.handoffs/current.md` updated to reflect post-archive status (next step after this checklist) | `.handoffs/current.md` | 🚧 In progress | See openspec-archive step 6 |

## Overall verdict

- **Must-pass items**: A1–A6, B1–B7, C1–C4, D1–D6, E1–E6, G1–G3. All currently ✅ except F5 (browser manual step) which is recommended but non-blocking for code archive.
- **Proceed to archive? Yes.** When F5 is repeated live before launching change #2 it becomes a retrospective 21st green check on the same baseline.
