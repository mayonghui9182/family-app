package com.familyapp.agent;

import com.familyapp.category.Category;
import com.familyapp.category.CategoryRepository;
import com.familyapp.task.Task;
import com.familyapp.task.TaskPriority;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskSource;
import com.familyapp.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Change 2 Task 2：C 组 18 端点集成测试。
 * Profile internal-api-test 走真实 X-Internal-Secret 链路；@Transactional 每用例回滚种子数据。
 * 端点名对齐规格 §3.4；错误分支断言中文错误码信封。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("internal-api-test")
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
@Transactional
class InternalAgentC1ToC18Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "test-secret-correct";
    private static final Long FID = 9101L;

    @Autowired MockMvc mvc;
    @Autowired TaskRepository taskRepository;
    @Autowired SubtaskRepository subtaskRepository;
    @Autowired NoteRepository noteRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired RuleTemplateRepository templateRepository;
    @Autowired RuleTemplateVersionRepository versionRepository;
    @Autowired TaskScheduleRepository scheduleRepository;
    @Autowired TaskTriggerRepository triggerRepository;
    @Autowired PendingScheduleService pendingScheduleService;

    private String fetchBody(String url) throws Exception {
        return mvc.perform(get(url).header("X-Internal-Secret", SECRET)).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Task seedTask(String title, TaskStatus status, TaskPriority priority, Long assignee) {
        Task t = new Task();
        t.setFamilyId(FID);
        t.setTitle(title);
        t.setStatus(status);
        t.setPriority(priority);
        t.setSource(TaskSource.MANUAL);
        t.setAssigneeId(assignee);
        return taskRepository.save(t);
    }

    // ==================== C-1 ====================

    @Test
    void C1_getTasks_listAndStatusFilter() throws Exception {
        seedTask("买菜", TaskStatus.PENDING, TaskPriority.MEDIUM, null);
        seedTask("缴费", TaskStatus.COMPLETED, TaskPriority.LOW, null);

        mvc.perform(get("/api/internal/agent/tasks?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data", hasSize(2)));

        mvc.perform(get("/api/internal/agent/tasks?family_id=" + FID + "&status=PENDING&keyword=买")
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("买菜"));
    }

    // ==================== C-2 ====================

    @Test
    void C2_getTaskDetail_nestedSubtasksAndNotes() throws Exception {
        Task t = seedTask("大扫除", TaskStatus.PENDING, TaskPriority.HIGH, null);
        Subtask s = new Subtask();
        s.setFamilyId(FID); s.setParentTaskId(t.getId()); s.setTitle("擦窗");
        s.setSortOrder(1); s.setStatus(TaskStatus.PENDING); s.setIsAiGenerated(true);
        subtaskRepository.save(s);
        Note n = new Note();
        n.setFamilyId(FID); n.setTargetType("task"); n.setTargetId(t.getId()); n.setContent("先通风");
        noteRepository.save(n);

        mvc.perform(get("/api/internal/agent/tasks/" + t.getId() + "?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("大扫除"))
                .andExpect(jsonPath("$.data.subtasks", hasSize(1)))
                .andExpect(jsonPath("$.data.subtasks[0].is_ai_generated").value(true))
                .andExpect(jsonPath("$.data.notes", hasSize(1)))
                .andExpect(jsonPath("$.data.notes[0].content").value("先通风"));
    }

    // ==================== C-3 ====================

    @Test
    void C3_createTask_sourceForcedAgent_categoryScopeChecked() throws Exception {
        Category cat = new Category();
        cat.setName("家务"); cat.setFamilyId(FID);
        cat = categoryRepository.save(cat);
        Long otherCat = categoryRepository.save(otherFamilyCategory()).getId();

        String body = "{\"title\":\"预约牙医\",\"category_id\":" + cat.getId()
                + ",\"priority\":\"HIGH\"}";
        mvc.perform(post("/api/internal/agent/tasks?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // 跨家庭分类 → 46002
        String bad = "{\"title\":\"越权任务\",\"category_id\":" + otherCat + "}";
        mvc.perform(post("/api/internal/agent/tasks?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json").content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(46002));

        assertThat(taskRepository.findAll().stream()
                .filter(t -> "预约牙医".equals(t.getTitle()))
                .allMatch(t -> t.getSource() == TaskSource.AGENT)).isTrue();
    }

    private Category otherFamilyCategory() {
        Category c = new Category();
        c.setName("别家"); c.setFamilyId(8888L);
        return c;
    }

    // ==================== C-4 ====================

    @Test
    void C4_batchSubtasks_createdCountAndEmptyGuard() throws Exception {
        Task t = seedTask("装修", TaskStatus.PENDING, TaskPriority.MEDIUM, null);
        String body = "{\"task_id\":" + t.getId() + ",\"subtasks\":["
                + "{\"title\":\"量尺寸\",\"sort_order\":1},"
                + "{\"title\":\"下单\",\"sort_order\":2},"
                + "{\"title\":\"验收\",\"sort_order\":3}]}";
        mvc.perform(post("/api/internal/agent/subtasks/batch?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created_count").value(3))
                .andExpect(jsonPath("$.data.ids", hasSize(3)));

        String empty = "{\"task_id\":" + t.getId() + ",\"subtasks\":[]}";
        mvc.perform(post("/api/internal/agent/subtasks/batch?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json").content(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(46004));
    }

    // ==================== C-5 ====================

    @Test
    void C5_updateTask_partialFields() throws Exception {
        Task t = seedTask("旧标题", TaskStatus.PENDING, TaskPriority.LOW, null);
        String body = "{\"title\":\"新标题\",\"priority\":\"HIGH\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/internal/agent/tasks/" + t.getId() + "?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("新标题"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"));

        assertThat(taskRepository.findById(t.getId()).orElseThrow().getTitle()).isEqualTo("新标题");
    }

    // ==================== C-6 / C-7 ====================

    @Test
    void C6_C7_updateThenDeleteSubtask() throws Exception {
        Task t = seedTask("母任务", TaskStatus.PENDING, TaskPriority.MEDIUM, null);
        Subtask s = new Subtask();
        s.setFamilyId(FID); s.setParentTaskId(t.getId()); s.setTitle("步骤A");
        s.setSortOrder(1); s.setStatus(TaskStatus.PENDING); s.setIsAiGenerated(true);
        s = subtaskRepository.save(s);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/internal/agent/subtasks/" + s.getId() + "?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"status\":\"IN_PROGRESS\",\"sort_order\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.sort_order").value(2));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/internal/agent/subtasks/" + s.getId() + "?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(subtaskRepository.findById(s.getId())).isEmpty();
    }

    // ==================== C-8 / C-9 ====================

    @Test
    void C8_C9_addThenDeleteNote_targetTypeGuard() throws Exception {
        Task t = seedTask("带便签任务", TaskStatus.PENDING, TaskPriority.MEDIUM, null);

        mvc.perform(post("/api/internal/agent/notes?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"target_type\":\"bogus\",\"target_id\":" + t.getId()
                                + ",\"content\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40099));

        String resp = mvc.perform(post("/api/internal/agent/notes?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"target_type\":\"task\",\"target_id\":" + t.getId()
                                + ",\"content\":\"周六上午做\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.target_type").value("task"))
                .andReturn().getResponse().getContentAsString();
        long noteId = ((com.fasterxml.jackson.databind.JsonNode)
                MAPPER.readTree(resp).get("data")).get("id").asLong();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/internal/agent/notes/" + noteId + "?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk());
        assertThat(noteRepository.findById(noteId)).isEmpty();

        // 跨家庭便签删除 → 40406
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/internal/agent/notes/" + noteId + "?family_id=8888")
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40406));
    }

    // ==================== C-10 ====================

    @Test
    void C10_getCategories() throws Exception {
        Category c1 = new Category(); c1.setName("清洁"); c1.setFamilyId(FID);
        categoryRepository.save(c1);
        Category c2 = new Category(); c2.setName("采买"); c2.setFamilyId(FID);
        categoryRepository.save(c2);

        mvc.perform(get("/api/internal/agent/categories?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    // ==================== C-11 ====================

    @Test
    void C11_getTemplates_typeAndActiveFilter() throws Exception {
        seedTemplate("分解模板", "decomposition", true);
        seedTemplate("日常模板", "routine", true);
        seedTemplate("停用日常", "routine", false);

        mvc.perform(get("/api/internal/agent/templates?family_id=" + FID
                        + "&type=routine&is_active=true")
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].type").value("routine"));
    }

    private RuleTemplate seedTemplate(String name, String type, boolean active) {
        try {
            RuleTemplate t = new RuleTemplate();
            t.setFamilyId(FID); t.setType(type); t.setName(name);
            t.setContent(MAPPER.readTree("{\"title\":\"" + name + "\"}"));
            if ("routine".equals(type)) {
                t.setConditions(MAPPER.readTree("{\"kind\":\"monthly\",\"day\":32}"));
            }
            t.setIsActive(active);
            return templateRepository.save(t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== C-12 ====================

    @Test
    void C12_fewShot_completedWithSubtasks_limit() throws Exception {
        Task done = seedTask("已完成样本", TaskStatus.COMPLETED, TaskPriority.MEDIUM, null);
        seedTask("未完成不算", TaskStatus.PENDING, TaskPriority.MEDIUM, null);
        Subtask s = new Subtask();
        s.setFamilyId(FID); s.setParentTaskId(done.getId()); s.setTitle("样本子步");
        s.setSortOrder(1); s.setStatus(TaskStatus.COMPLETED); s.setIsAiGenerated(true);
        subtaskRepository.save(s);

        mvc.perform(get("/api/internal/agent/templates/few-shot?family_id=" + FID + "&limit=10")
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tasks_with_subtasks", hasSize(1)))
                .andExpect(jsonPath("$.data.tasks_with_subtasks[0].subtasks", hasSize(1)));
    }

    // ==================== C-13 ====================

    @Test
    void C13_workload_countsPendingCompletedHighPriority() throws Exception {
        seedTask("待办1", TaskStatus.PENDING, TaskPriority.MEDIUM, null);
        seedTask("待办高优", TaskStatus.PENDING, TaskPriority.HIGH, null);
        seedTask("待办他人", TaskStatus.PENDING, TaskPriority.MEDIUM, 77L);
        seedTask("完成1", TaskStatus.COMPLETED, TaskPriority.LOW, null);
        seedTask("完成2", TaskStatus.COMPLETED, TaskPriority.LOW, null);

        mvc.perform(get("/api/internal/agent/workload?family_id=" + FID + "&window_days=7")
                        .header("X-Internal-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pending").value(3))
                .andExpect(jsonPath("$.data.completed").value(2))
                .andExpect(jsonPath("$.data.high_priority").value(1));
    }

    // ==================== C-14 ====================

    @Test
    void C14_summaryLog_writeAndReadBack() throws Exception {
        String body = "{\"status\":\"success\",\"categories_updated\":[1,2],"
                + "\"failed_categories\":[],\"trigger_source\":\"auto\"}";
        String resp = mvc.perform(post("/api/internal/agent/summary-log?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn().getResponse().getContentAsString();
        assertThat(MAPPER.readTree(resp).at("/data/id").asLong()).isPositive();

        mvc.perform(post("/api/internal/agent/summary-log?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"status\":\"bogus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40099));
    }

    // ==================== C-15 ====================

    @Test
    void C15_dueToday_hitsWeeklyRoutine() throws Exception {
        int todayIso = LocalDate.now().getDayOfWeek().getValue();
        RuleTemplate hit = seedTemplate("今日日常", "routine", true);
        hit.setConditions(MAPPER.readTree(
                "{\"kind\":\"weekly\",\"weekdays\":[" + todayIso + "]}"));
        hit.setContent(MAPPER.readTree(
                "{\"title\":\"浇花\",\"priority\":\"LOW\",\"needs_ai\":false}"));
        templateRepository.save(hit);
        seedTemplate("永不到期", "routine", true); // monthly day=32 不命中

        String resp = fetchBody("/api/internal/agent/routines/due-today?family_id=" + FID);
        var node = MAPPER.readTree(resp);
        assertThat(node.get("code").asInt()).isZero();
        assertThat(node.withArray("data").size()).isEqualTo(1);
        assertThat(node.at("/data/0/routine_title").asText()).isEqualTo("浇花");
        assertThat(node.at("/data/0/needs_ai").asBoolean()).isFalse();
    }

    // ==================== C-16 ====================

    @Test
    void C16_claimPending_atomicDoubleClaim() throws Exception {
        LocalDate today = LocalDate.now();
        TaskSchedule sch = new TaskSchedule();
        sch.setFamilyId(FID); sch.setTriggerDate(today);
        sch.setTargetTemplate(MAPPER.readTree("{\"title\":\"预约牙医\"}"));
        sch = scheduleRepository.save(sch);

        String first = fetchBody("/api/internal/agent/schedules/pending?family_id=" + FID);
        assertThat(MAPPER.readTree(first).at("/data/id").asLong()).isEqualTo(sch.getId());

        String second = fetchBody("/api/internal/agent/schedules/pending?family_id=" + FID);
        assertThat(MAPPER.readTree(second).at("/data").isNull()).isTrue();

        assertThat(scheduleRepository.findById(sch.getId()).orElseThrow().getIsExecuted()).isTrue();
    }

    /** design.md D1 并发语义：N 并发抢同一调度，恰有一个成功。 */
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void C16_concurrentClaim_exactlyOneWinner() throws Exception {
        TaskSchedule sch = new TaskSchedule();
        sch.setFamilyId(FID); sch.setTriggerDate(LocalDate.now());
        sch.setTargetTemplate(MAPPER.readTree("{\"title\":\"并发目标\"}"));
        scheduleRepository.save(sch);

        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit((Callable<Integer>) () -> {
                ready.countDown();
                go.await();
                return pendingScheduleService.claimNext(FID).isPresent() ? 1 : 0;
            }));
        }
        ready.await();
        go.countDown();
        int winners = 0;
        for (Future<Integer> f : futures) {
            winners += f.get();
        }
        pool.shutdown();
        assertThat(winners).as("并发下恰有一个领取者").isEqualTo(1);
    }

    // ==================== C-17 ====================

    @Test
    void C17_onCompleted_directTrigger_andDedup() throws Exception {
        Task src = seedTask("源任务", TaskStatus.COMPLETED, TaskPriority.MEDIUM, null);
        TaskTrigger trig = new TaskTrigger();
        trig.setFamilyId(FID); trig.setSourceTaskId(src.getId());
        trig.setTargetTemplate(MAPPER.readTree(
                "{\"title\":\"下游直建\",\"needs_ai\":false,\"priority\":\"HIGH\"}"));
        trig.setIsActive(true);
        triggerRepository.save(trig);

        mvc.perform(post("/api/internal/agent/task-event/on-completed?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"source_task_id\":" + src.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggered").value(1));

        assertThat(taskRepository.findByFamilyIdAndDeletedAtIsNull(FID).stream()
                .anyMatch(t -> "下游直建".equals(t.getTitle())
                        && t.getPriority() == TaskPriority.HIGH)).isTrue();

        // 5 分钟去重窗口内第二次 → triggered=0
        mvc.perform(post("/api/internal/agent/task-event/on-completed?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"source_task_id\":" + src.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggered").value(0));
    }

    /** B-1 降级链路端到端：agent-service 未监听 → 连接失败 → 直建 + 中文提示便签。 */
    @Test
    void C17_onCompleted_aiFallback_createsPlainTaskWithNote() throws Exception {
        Task src = seedTask("AI 源任务", TaskStatus.COMPLETED, TaskPriority.MEDIUM, null);
        TaskTrigger trig = new TaskTrigger();
        trig.setFamilyId(FID); trig.setSourceTaskId(src.getId());
        trig.setTargetTemplate(MAPPER.readTree(
                "{\"title\":\"AI 下游\",\"needs_ai_decomposition\":true}"));
        trig.setIsActive(true);
        triggerRepository.save(trig);

        mvc.perform(post("/api/internal/agent/task-event/on-completed?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"source_task_id\":" + src.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggered").value(1));

        List<Task> downstream = taskRepository.findByFamilyIdAndDeletedAtIsNull(FID).stream()
                .filter(t -> "AI 下游".equals(t.getTitle())).toList();
        assertThat(downstream).hasSize(1);
        assertThat(downstream.get(0).getStatus()).isEqualTo(TaskStatus.PENDING);

        List<Note> notes = noteRepository
                .findByTargetTypeAndTargetIdOrderByIdAsc("task", downstream.get(0).getId());
        assertThat(notes).anyMatch(nt -> nt.getContent().contains("AI 拆解未响应"));
    }

    // ==================== C-18 ====================

    @Test
    void C18_newVersion_transactionalIncrement() throws Exception {
        RuleTemplate tpl = seedTemplate("版本模板", "decomposition", true);
        tpl.setVersion(3);
        templateRepository.save(tpl);
        RuleTemplateVersion oldRow = new RuleTemplateVersion();
        oldRow.setFamilyId(FID); oldRow.setTemplateId(tpl.getId());
        oldRow.setVersion(3); oldRow.setChangeSummary("v3");
        oldRow.setIsImportant(false);
        oldRow.setContent(MAPPER.readTree("{\"steps\":[\"旧\"]}"));
        oldRow = versionRepository.save(oldRow);

        String resp = mvc.perform(post("/api/internal/agent/templates/" + tpl.getId()
                        + "/new-version?family_id=" + FID)
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"content\":{\"steps\":[\"新步骤\"]},"
                                + "\"change_summary\":\"改为新步骤\",\"is_important\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.new_version").value(4))
                .andReturn().getResponse().getContentAsString();
        RuleTemplateVersion newest =
                versionRepository.findTopByTemplateIdOrderByVersionDesc(tpl.getId()).orElseThrow();
        assertThat(newest.getVersion()).isEqualTo(4);
        assertThat(newest.getParentVersionId()).isEqualTo(oldRow.getId());
        assertThat(newest.getIsImportant()).isTrue();

        RuleTemplate reloaded = templateRepository.findById(tpl.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(4);
        assertThat(reloaded.getParentVersionId()).isEqualTo(newest.getId());
        assertThat(reloaded.getContent().toString()).contains("新步骤");

        // 跨家庭模板访问 → 40404
        mvc.perform(post("/api/internal/agent/templates/" + tpl.getId() + "/new-version?family_id=8888")
                        .header("X-Internal-Secret", SECRET)
                        .contentType("application/json")
                        .content("{\"content\":{},\"change_summary\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40404));
    }
}
