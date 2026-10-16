# Spec Delta: Change 6 Frontend Views + Backend Proxy

## ADDED Routes

### R-1 Path "/dashboard" -> DashboardView
- Requires auth. Shows three MetricCards at top(X3), recent 10 tasks table, AI quick-decompose card, daily summary panel with manual trigger(OWNER/ADULT only)

### R-2 Path "/chat" -> ChatView
- Shows decompose progress stepper + SSE real-time messages + cancel btn. Receives decompose params from query or TasksView form.

### R-3 Path "/templates" -> TemplatesView
- Three tabs: Template List(filter by category, version history, star toggle), Create Template(form editor), Pick Template for Task(search + select)

### R-4 Path "/automation" -> AutomationView
- Three metric cards at top, X2 warning alerts, summary execution log table, OWNER/ADULT manual trigger switch

### R-5 Root redirect change
- Previously "/" child redirects to "/tasks"; now redirects to "/dashboard"

## ADDED Components

### MetricCard.vue(src/components/MetricCard.vue)
- Props: title:String, value:Number, unit:String, percentStatus:Number|null, emoji:String, delta:Number|String|null
- Color based on percentStatus: <50%->red, 50-74%->yellow, >=75%->green, null->default blue

### SseStatusBar.vue(src/components/SseStatusBar.vue)
- Fixed-position bar: connecting/connected/reconnecting(X8 exponential backoff countdown)

## ADDED Stores

### sseClient.js(src/stores/sseClient.js)
- State: status("idle"/"connecting"/"connected"/"slow"), reconnectState, alertVisible
- Actions: connect(path, callbacks), retryNow(). Uses utils/sseReconnect.js nextDelay/forceReconnectNow

### decompose.js(src/stores/decompose.js)
- State: status("idle"/"classifying"/"reasoning"/"proposing"/"confirmed"/"cancelled"/"error")
- Proposal data: {task_id, title, category, subtasks:[], title_reasoning_cn, category_reasoning_cn}
- Actions: commitClassify(data), commitReason(data), commitProposal(data), finishDecompose(data), setError(err)

### templates.js(src/stores/templates.js)
- State: list[], selectedVersion, creating, loading
- Actions: load(), loadVersions(tplId), toggleStar(vId,bool), createVersion(tplId,body), deleteVersion(vId), pickVersionForTask(tplId,vId,taskId)

### summary.js(src/stores/summary.js)
- State: triggerPending/null, lastSummary, metrics, isLoading
- Actions: trigger(familyId,force), pollMetrics(familyId,windowDays), list(familyId,params)

## ADDED API Modules

### api/decompose.js
- start(payload): POST /api/agent/v1/decompose/start -> SSE stream via fetch
- cancel(familyId,taskId): POST /api/agent/v1/decompose/{familyId}/{taskId}/cancel
- confirm(familyId,taskId,body): POST /api/agent/v1/decompose/{familyId}/{taskId}/confirm

### api/templates.js
- list(params): GET /api/agent/v1/templates | versions(tplId): GET /api/agent/v1/templates/{tplId}/versions
- createVersion(tplId,body): POST /api/agent/v1/templates/{tplId}/versions
- toggleStar(vId,bool): PATCH /api/agent/v1/templates/versions/{vId}/star
- deleteVersion(vId): DELETE /api/agent/v1/templates/versions/{vId}
- pickVersionForTask(tplId,vId,taskId): POST /api/agent/v1/templates/{tplId}/versions/{vId}/pick

### api/sdkSummary.js
- trigger(familyId,force): GET /api/agent/v1/summary/trigger/{familyId}?force=true
- list(familyId,params): GET /api/agent/v1/summary/list/{familyId}
- metrics(familyId,window): GET /api/agent/v1/summary/metrics/{familyId}?window_days=30

## NEW Backend Endpoints(Java Spring Boot)

### C-25 POST /api/agent/v1/decompose/start
- Public-facing facade. Forwards user token+family_id to agent-service POST /api/v1/chat/stream. Returns SSE ServerSentEvent stream.

### C-26 POST /api/agent/v1/decompose/{familyId}/{taskId}/cancel
- Forwards cancellation request to backend C-group or agent-service internal endpoint.

### C-27 GET /api/agent/v1/templates
- Facade mapping to InternalAgentController GET /api/internal/agent/templates. Adds public auth layer.

### C-28 GET /api/agent/v1/templates/{id}/versions
- Same logic as C-24 but exposed publicly(no internal-secret required).

### C-29 PATCH /api/agent/v1/templates/versions/{v}/star
- NEW: Toggle is_important on rule_template_versions. Family isolation enforced.

### C-30 DELETE /api/agent/v1/templates/versions/{v}
- NEW: Delete a template version row. Cascading cleanup of references.

### C-31 POST /api/agent/v1/templates/{tplId}/versions/{v}/pick
- NEW: Assign template version to a task. Creates link between task and template version.

### C-32 GET /api/agent/v1/summary/trigger/{familyId}
- Facade to agent-service POST /api/v1/summary/trigger. Adds family validation before forwarding.

### C-33 GET /api/agent/v1/summary/list/{familyId}
- Reads summary_execution_log FROM postgres. Returns paginated recent summaries for family.

### C-34 GET /api/agent/v1/summary/metrics/{familyId}
- Computed aggregation SQL: success_rate_30d, template_version_count_30d, learned_adjustments_30d. All scoped to family_id.

## MODIFIED Files

- frontend/src/router/index.js: add dashboard/chat/templates/automation routes, change root redirect
- frontend/src/api/client.js: extend needFamilyHeader() to cover /agent/* paths
- frontend/src/views/TaskView.vue: add Tab2 AI Decompose form -> navigate /chat or show Dialog
- frontend/src/views/InviteView.vue: no functional changes. Add aria-labels, canvas font-store scale compatibility.
