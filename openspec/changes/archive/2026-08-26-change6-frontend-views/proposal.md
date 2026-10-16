# Proposal: Change 6 - Frontend 4 Views + Legacy Refactors + X3 X4 X8

## Objective
- Deliver 4 new Vue 3 views: DashboardView (X4 root route), ChatView (decompose flow), TemplatesView (template management 3 tabs), AutomationView (X3 metric cards + X2 warnings + manual summary)
- Modify 2 existing views: TaskView (new "AI Decompose" tab), InviteView (aria-label + font store scale compatibility)
- Implement X3: three metric UI cards (30d AI decompose success rate / template version count / learning count) on Dashboard/Automation
- Implement X4: root route "/" redirects to DashboardView (not TasksView)
- Implement X8: SSE exponential backoff reconnect (BASE_DELAY 3s -> MAX_DELAY 24s; 3 consecutive failures -> 30s slow mode + screen alert + one-click retry)
- Build SDK: decompose/templates/summary API wrappers as Pinia stores + api/* modules
- Build MetricCard reusable component (color thresholds: <50% red / 50-74% yellow / >=75% green)
- 14+ Vitest tests all green + npm run test:unit passes
- Full Chinese elder-friendly: min-height 56px buttons, emoji icons, WCAG aria-labels

## Out of Scope
- No backend business logic changes (proxy layer only forwards/aggregates)
- No Docker/CI changes (deferred to Change 7)
- No agent-service Python modifications (contract bridged via frontend SDK + backend proxy layer)

## Specs
See specs/change6-frontend/spec.md
