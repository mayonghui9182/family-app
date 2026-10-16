# Design: Change 6 - Frontend 4 Views + Legacy Refactors + X3 X4 X8

## D1: Backend Proxy /api/agent/** Layer Architecture Decision

**Current state:** agent-service A-group public routes use /api/v1 prefix (D7 deviation recorded). Backend has only /api/internal/agent/** C-group endpoints (requires internal secret header). Change 5 design explicitly states 'Change 6 by backend proxy /api/agent/**' but proxy controller not yet implemented.

**Solution:** Change 6 adds AgentProxyController (Java/Spring Boot 3.2) at /api/agent/v1/** forwarding to agent-service(port 8700). Proxy layer duties:
- Receives user X-Access-Token + X-Family-Id (already handled by authInterceptor/familyContextInterceptor)
- Forwards same token+family_id headers to agent-service (AuthVerifier re-validates via backend api/auth/me)
- Internal secret never leaves backend
- SSE stream forwarded via Spring WebFlux ServerSentEvent or Servlet AsyncContext StreamingResponseBody

**Key implementation note:** Spring MVC buffers HTTP response by default; SSE may be truncated. Two options:
(a) Use WebFlux @RestController + ResponseEntity<Flux<ServerSentEvent>> (recommended, native SSE chunking)
(b) Use original Servlet + AsyncContext + StreamingResponseBody (ensure setChunkedInput(true))

**Data flow:** Browser(:5173) -> Vite proxy(/api->8080) -> AgentProxyController(/api/agent) -> HttpClient -> agent-service(:8700/api/v1/chat/stream) -> SSE <- chunk <- AgentProxyController <- ResponseBody -> DevServer -> Browser fetch reader



## D2: SSE POST Stream vs GET EventSource

**agent-service A-1 actual contract:** POST /api/v1/chat/stream, body ChatStreamRequest{message}, response text/event-stream.

**Plan assumption error:** Plan says EventSource(native GET-based). agent-service does NOT support GET SSE. Must use fetch+ReadableStream instead.

**Frontend sseClient.js implementation:** 
- fetch(url,{method:POST,headers:{X-Family-Id:x,X-Access-Token:x,Content-Type:application/json},body:JSON.stringify({message:title})})
- Read response.body.pipeThrough(new TextDecoderStream()) line-by-line parse SSE format: event:xxx + data:yyy\\n\\n
- Event type mapping: classification/reasoning/proposal/done/error -> stores.decompose.commitX/setError
- Reconnect logic reuses utils/sseReconnect.js state machine(nextDelay/forceReconnectNow)


## D3: A-3 Summary List & A-4 Metrics Data Sources

**A-3 list:** Not in agent-service or backend. Source: backend summary_execution_log table. New endpoint C-33 queries it for family.

**A-4 metrics(X3 card data):** Not in any service. Needs new backend C-34 computed SQL aggregation:
- success_rate_30d = COMPLETED/(COMPLETED+CANCELLED+FAILED)*100 (30d tasks by family_id)
- template_version_count_30d = COUNT(rule_template_versions WHERE created_at >= now-30d AND family_id=fid)
- learned_adjustments_30d = COUNT(DISTINCT diff_engine-triggered new versions >= 3 threshold count), needs JOIN on rule_template_versions.change_summary containing auto-summary rows

**SQL caution:** X3 metrics aggregation uses PostgreSQL-specific functions(json_agg etc.). Per D11/D12 lesson: at least one assertion must pass through real PG, H2 mocks verify semantic equivalence only.


## D4: Templates REST API Upgrade

**Existing C-group templates:** GET/templates, GET/templates/{id}/versions, POST/templates/{id}/new-version, POST/templates/{id}/change-summary, POST/template-versions/cleanup, GET/templates/few-shot -- all under /api/internal/agent/** prefix.

**Plan-required I-4~I-7 endpoints DO NOT EXIST:** PATCH/star, DELETE/version, POST/pick(task_id). These are new backend endpoints.

**Design:** Add C-29(I-4 star), C-30(I-5 delete), C-31(I-6/I-7 pick) as independent endpoints in AgentProxyController. is_important toggle directly updates rule_template_versions; delete cascades cleanup; pick links template version to task.

## D5: Router X4 Root Redirect

**Before:** "/" child redirects to "/tasks". **After:** "/" child redirects to "/dashboard" + new route "dashboard" -> DashboardView.

## D6: MetricCard Component Spec

Props: title(String), value(Number), unit(String), percentStatus(Number|null), emoji(String), delta(Number|String|null)
Color: percentStatus non-null: <50% red, 50-74% yellow, >=75% green; null -> default blue
formattedValue: round(value)+"%" if percentage, else value+unit. delta in small gray font.

## D7: client.js Interceptor Extension

Current needFamilyHeader covers only /tasks and /categories. New SDK paths(/agent/*,/internal/templates) also need X-Family-Id.
Fix: make needFamilyHeader broader: exclude /auth/** URLs only. Backward compatible.

## D8: Store State Machines
decompose: idle/classifying/reasoning/proposing/confirmed/cancelled/error
templates: list[], creating, updating, starred(pendingOptimistic), deleted
summary: triggerPending/null, lastSummary, metrics, isLoading
sseClient: status(idle/connecting/connected/slow/alertVisible), reconnectState, forceReconnect()

## D9: Elder-Friendly Constraints
Default font size large(localStorage no record). Buttons min-height 56px. WCAG AA>=4.5:1 contrast. Emoji+Chinese button text. Focus-visible blue 3px border. aria-live="polite" for SSE update regions.
