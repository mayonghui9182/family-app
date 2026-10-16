# task-planner/ai-decomposition Delta Specification

## Purpose

会话式 AI 拆解模块：agent-service 通过 `POST /api/chat` 以 SSE 流式返回「分类 → 推理 → 提案 → 完成」事件，先在既有分类中归类，再在分类内匹配 decomposition 规则模板，结合同分类历史已办任务 few-shot 与当前负载上下文调用 LLM 拆出 2-8 条子待办，并经 MCP 在 task-service 落库为 24 小时过期的 draft 提案；含冷启动、JSON 解析容错、30 秒超时降级与错误事件契约。

## ADDED Requirements

### Requirement: Chat endpoint streams the decomposition over SSE

agent-service SHALL expose `POST /api/chat` accepting a JSON body with a required non-empty user `message` and SHALL respond with `Content-Type: text/event-stream`. On a successful run the stream SHALL emit, in this order: one `classification` event, one or more `reasoning` events, one `proposal` event and one `done` event. Every event `data` SHALL be a JSON object. On any failure the stream SHALL emit a single `error` event and then close; `proposal` and `done` SHALL NOT be emitted after an `error`. A request whose `message` is missing or blank SHALL be rejected with 400 before any stream is opened.

#### Scenario: Successful run emits the documented event order

- **WHEN** a client posts `{"message":"下周三准备产品发布会，需要做20分钟演示"}` to `/api/chat`
- **THEN** the received event names in order are `classification`, one or more `reasoning`, `proposal`, `done`

#### Scenario: Reasoning is streamed in chunks

- **WHEN** the LLM produces the reasoning text during decomposition
- **THEN** the client receives it as multiple `reasoning` events whose `data.chunk` values concatenate to the full reasoning

#### Scenario: Blank message is rejected without a stream

- **WHEN** a client posts `{"message":"   "}` to `/api/chat`
- **THEN** the response status is 400 and the content type is not `text/event-stream`

### Requirement: Classification only uses existing categories

Before decomposition the agent SHALL fetch the category list via the MCP tool `get_categories` and SHALL ask the LLM to choose exactly one category for the user message. When no existing category matches, the task SHALL be assigned to the reserved category named `未分类` and the system SHALL NOT create a new category. The `classification` event SHALL carry `category` (the category name) and `confidence` (a number between 0 and 1).

#### Scenario: Matching category is reported with confidence

- **WHEN** categories `市场推广` and `工作汇报` exist and the message is "准备Q4产品发布会"
- **THEN** the `classification` event data contains `category` equal to `"市场推广"` and `confidence` within [0, 1]

#### Scenario: Unmatched message falls back to 未分类 without creating a category

- **WHEN** the message does not match any existing category
- **THEN** the `classification` event data `category` equals `"未分类"` and `GET /api/categories` returns the same set of categories as before the request

### Requirement: Template matching inside the classified category

The agent SHALL fetch the active decomposition templates of the classified category via the MCP tool `get_rule_templates` and SHALL let the LLM pick the best match from the task description and the templates' `common_steps` summaries. A category MAY hold several decomposition templates. When only an approximate match exists, the LLM SHALL adapt it and `agent_reasoning` SHALL state that template X was used as a reference with adjustments ("参考模板X但有所调整"). When no template matches, or the category has no decomposition template, the agent SHALL decompose from the LLM's general ability (cold start) and `agent_reasoning` SHALL state that there is no historical reference ("无历史参考"); no default fallback template SHALL be used. Templates with `is_active=false` SHALL NOT be candidates.

#### Scenario: Matching template guides the decomposition

- **WHEN** the category `市场推广` holds an active template "发布会准备" with steps 确定主题/制作PPT/彩排/物料准备 and the message is "准备产品发布会"
- **THEN** the proposal subtasks follow that step pattern and `agent_reasoning` references the template

#### Scenario: Cold start annotates missing history

- **WHEN** the classified category has no decomposition template
- **THEN** the proposal is still produced and `agent_reasoning` contains "无历史参考"

#### Scenario: Deactivated template is not a candidate

- **WHEN** the only decomposition template of the category has `is_active=false`
- **THEN** the agent takes the cold-start path and the deactivated template is not named in `agent_reasoning` as the applied template

### Requirement: Decomposition context includes history few-shot and workload

The agent SHALL fetch completed tasks of the same category via the MCP tool `get_completed_tasks_by_category` and include them as few-shot examples of "task → subtasks", and SHALL fetch the current workload via the MCP tool `get_current_workload` (unfinished task count, priority distribution, due-soon count) and include it in the decomposition prompt. When the workload contains 3 or more unfinished high-priority tasks, `agent_reasoning` SHALL include a workload reminder. Missing history or missing workload data SHALL NOT abort the run.

#### Scenario: History is used as few-shot reference

- **WHEN** the category has two completed tasks with subtasks and the message asks for a similar task
- **THEN** `agent_reasoning` references the historical pattern (for example "基于历史N次…")

#### Scenario: High workload produces a reminder

- **WHEN** `get_current_workload` reports 3 unfinished high-priority tasks
- **THEN** `agent_reasoning` contains a workload reminder

#### Scenario: Cold system without history still decomposes

- **WHEN** the category has no completed task and no template
- **THEN** the run completes with a proposal and no `error` event is emitted

### Requirement: Proposal payload and draft persistence through MCP

Before emitting the `proposal` event the agent SHALL persist the proposal through MCP: `create_task` with `status=draft`, `source=agent` and `expires_at` equal to the creation time plus 24 hours, followed by `create_subtasks` with `is_ai_generated=true`. The `proposal` event data SHALL contain `task_id`, `task_title`, `task_description`, `category`, `category_confidence`, `subtasks` (each with `title`, `description`, `sort_order`, `notes`), `agent_reasoning` and `expires_at`. The `done` event data SHALL contain the same `task_id` and `status` equal to `"draft_created"`. The user confirms a proposal by calling task-service `PUT /api/tasks/{id}` directly; agent-service SHALL NOT expose a confirmation endpoint.

#### Scenario: Draft task is created before the proposal event

- **WHEN** a decomposition run succeeds
- **THEN** a task exists in task-service with status `draft`, source `agent` and a non-null `expires_at`, and the `proposal` event `task_id` equals that task's identifier

#### Scenario: Proposal subtasks are stored as AI-generated

- **WHEN** the `proposal` event lists three subtasks
- **THEN** `GET /api/tasks/{task_id}` returns three subtasks, all with `is_ai_generated` true and `sort_order` 1, 2, 3

#### Scenario: Done event reports the draft creation

- **WHEN** the stream reaches its end on a successful run
- **THEN** the `done` event data equals `{"task_id":"<uuid>","status":"draft_created"}`

### Requirement: Decomposition produces between two and eight subtasks

The decomposition SHALL request 2 to 8 concrete, executable subtasks. If the model returns more than 8, the agent SHALL keep the first 8 ordered by `sort_order` and note the truncation in `agent_reasoning`. If the model returns fewer than 2, the agent SHALL retry the decomposition call once; a second out-of-range result SHALL be reported through an `error` event and SHALL NOT produce a proposal.

#### Scenario: Excessive subtasks are truncated to eight

- **WHEN** the model returns 11 subtasks
- **THEN** the created draft has exactly 8 subtasks and `agent_reasoning` mentions the truncation

#### Scenario: Too few subtasks triggers one retry then an error

- **WHEN** the model returns a single subtask twice in a row
- **THEN** the stream emits an `error` event and no `proposal` event

### Requirement: LLM JSON output is enforced and parsed tolerantly

Every LLM call in the decomposition path SHALL request JSON output (`response_format: {"type":"json_object"}`). If parsing the model response fails, the agent SHALL extract the first balanced `{...}` block with a regular expression and retry parsing once. If parsing still fails the agent SHALL log the failure, emit an `error` event whose `message` is "拆解失败，请重试", and SHALL NOT emit `proposal` or `done`.

#### Scenario: Malformed wrapper is recovered by regex extraction

- **WHEN** the model returns prose followed by a valid JSON object
- **THEN** the agent extracts the JSON object and the run completes with a `proposal` event

#### Scenario: Unparsable output surfaces the retry message

- **WHEN** the model returns text from which no JSON object can be extracted, twice
- **THEN** the stream emits `error` with data `{"message":"拆解失败，请重试"}` and no draft task proposal is announced

### Requirement: Decomposition times out after 30 seconds and degrades to an error event

The whole decomposition run SHALL be bounded by a 30 second timeout. On timeout, on an LLM transport failure or on an MCP failure the agent SHALL emit an `error` event prompting a retry and SHALL NOT emit `proposal` or `done`. If the draft task had already been created before the failure, it SHALL remain in status `draft` with its `expires_at` and be cleaned up by the expiry mechanism rather than being deleted synchronously.

#### Scenario: LLM timeout emits an error event

- **WHEN** the LLM does not answer within 30 seconds
- **THEN** the stream emits a single `error` event and closes

#### Scenario: MCP unavailability emits an error event

- **WHEN** task-service `/mcp` cannot be reached during the run
- **THEN** the stream emits an `error` event whose message indicates the decomposition failed and can be retried

#### Scenario: Failure after task creation leaves an expiring draft

- **WHEN** `create_task` succeeded but `create_subtasks` failed
- **THEN** the stored task still has status `draft` with a non-null `expires_at`, and the client received an `error` event instead of `proposal`

### Requirement: Proposal evidence and learned context survive persistence

The agent SHALL pass the matched template identity/version and each subtask's notes to MCP persistence. Task-service SHALL atomically save the original proposal snapshot, subtasks and notes. The next decomposition SHALL include learned template notes and estimated count in its prompt while enforcing the 2–8 bound. LLM client initialization failures including missing credentials SHALL use the same SSE error contract.

#### Scenario: Notes remain after reopening the task

- **WHEN** a proposal includes non-empty notes on a subtask
- **THEN** task detail returns those notes after creation

#### Scenario: Learned notes guide the next decomposition

- **WHEN** summary learning updates a template's notes and estimated count
- **THEN** the subsequent decomposition prompt includes both values

#### Scenario: Missing key returns an error event

- **WHEN** chat is requested without a configured LLM API key
- **THEN** the stream emits one error event without proposal or done
