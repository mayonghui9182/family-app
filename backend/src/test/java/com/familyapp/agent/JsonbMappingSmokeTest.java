package com.familyapp.agent;

import com.familyapp.task.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSONB 映射冒烟测试（Change 2 Task 2 前置验证）。
 *
 * <p>目的：在构建 18 端点与 4 个自动化 Service 之前，先验证
 * {@code @JdbcTypeCode(SqlTypes.JSON)} + Jackson {@link JsonNode} 映射 + 原生条件 UPDATE
 * 在 H2 测试栈上可用。生产 PostgreSQL 的 jsonb 列由 ddl-auto=validate 校验
 * （方言报告类型 jsonb，一致即通过）。不用 String 承载 JSONB —— Hibernate 6 会把字符串
 * 再当 JSON 文档编码：写库成带引号转义的字符串文档，读回多一层；
 * JsonNode 结构化写入/读取则与期望结构相等。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JsonbMappingSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired RuleTemplateRepository ruleTemplateRepository;
    @Autowired RuleTemplateVersionRepository versionRepository;
    @Autowired TaskScheduleRepository taskScheduleRepository;
    @Autowired SubtaskRepository subtaskRepository;
    @Autowired NoteRepository noteRepository;
    @Autowired TaskTriggerRepository taskTriggerRepository;
    @Autowired SummaryExecutionLogRepository summaryLogRepository;
    @Autowired EntityManager em;

    /** 用例1：rule_templates 双 JSONB 字段写入 → 读回结构相等（H2 json 类型往返）。 */
    @Test
    void ruleTemplate_jsonb_conditions_content_roundTrip() throws Exception {
        JsonNode conditions = MAPPER.readTree("{\"kind\":\"weekly\",\"weekdays\":[1,2,3,4,5],\"time\":\"20:00\"}");
        JsonNode content = MAPPER.readTree("{\"title\":\"检查书包\",\"needs_ai\":false}");

        RuleTemplate tpl = new RuleTemplate();
        tpl.setFamilyId(9001L);
        tpl.setType("routine");
        tpl.setName("冒烟模板");
        tpl.setConditions(conditions);
        tpl.setContent(content);
        RuleTemplate saved = ruleTemplateRepository.saveAndFlush(tpl);

        // 清空一级缓存后重新加载，验证真实落库读回而非同事务内对象复用
        em.clear();

        RuleTemplate reloaded = ruleTemplateRepository.findById(saved.getId()).orElseThrow();
        // JsonNode.equals 为结构相等：若发生双重编码读回会是 TextNode，此处即失败
        assertEquals(conditions, reloaded.getConditions());
        assertEquals(content, reloaded.getContent());
    }

    /** 用例2：task_schedules 条件 UPDATE 原子抢占语义（design.md D1 SQL 在 H2 可执行）。 */
    @Test
    void taskSchedule_conditionalUpdate_claimSemantics() throws Exception {
        TaskSchedule s = new TaskSchedule();
        s.setFamilyId(9001L);
        s.setTriggerDate(LocalDate.now());
        s.setTargetTemplate(MAPPER.readTree("{\"title\":\"预约牙医\"}"));
        TaskSchedule saved = taskScheduleRepository.saveAndFlush(s);
        em.clear();

        var candidate = taskScheduleRepository.findNextPendingId(9001L);
        assertTrue(candidate.isPresent(), "应能找到今日未执行调度");
        assertEquals(1, taskScheduleRepository.claimById(candidate.get()), "首次抢占应影响 1 行");
        assertEquals(0, taskScheduleRepository.claimById(candidate.get()), "二次抢占应影响 0 行（已执行）");

        TaskSchedule claimed = taskScheduleRepository.findById(candidate.get()).orElseThrow();
        assertTrue(claimed.getIsExecuted(), "抢占后 is_executed 应为 true");
        assertTrue(taskScheduleRepository.findNextPendingId(9001L).isEmpty(), "消化后不应再有候选");
        assertTrue(saved != null);
    }

    /** 用例3：其余 5 张新表基础持久化（含 JSONB 单列表）全部可写可读。 */
    @Test
    void remainingTables_basicPersist() throws Exception {
        Subtask st = new Subtask();
        st.setFamilyId(9001L);
        st.setParentTaskId(1L);
        st.setTitle("收拾客厅");
        st.setSortOrder(1);
        st.setStatus(TaskStatus.PENDING);
        st.setIsAiGenerated(true);
        subtaskRepository.saveAndFlush(st);

        Note note = new Note();
        note.setFamilyId(9001L);
        note.setTargetType("task");
        note.setTargetId(1L);
        note.setContent("周末大扫除建议周六上午开始");
        noteRepository.saveAndFlush(note);

        RuleTemplateVersion v = new RuleTemplateVersion();
        v.setFamilyId(9001L);
        v.setTemplateId(1L);
        v.setVersion(2);
        v.setContent(MAPPER.readTree("{\"common_steps\":[\"收拾客厅\"]}"));
        v.setChangeSummary("冒烟版本");
        v.setIsImportant(true);
        versionRepository.saveAndFlush(v);

        TaskTrigger trigger = new TaskTrigger();
        trigger.setFamilyId(9001L);
        trigger.setSourceTaskId(1L);
        trigger.setTargetTemplate(MAPPER.readTree("{\"title\":\"下游任务\",\"needs_ai_decomposition\":true}"));
        taskTriggerRepository.saveAndFlush(trigger);

        SummaryExecutionLog logRow = new SummaryExecutionLog();
        logRow.setFamilyId(9001L);
        logRow.setStatus("success");
        logRow.setCategoriesUpdated(MAPPER.readTree("[1,2]"));
        summaryLogRepository.saveAndFlush(logRow);

        assertTrue(subtaskRepository.findByParentTaskIdOrderBySortOrderAscIdAsc(1L).size() >= 1);
        assertTrue(noteRepository.findByTargetTypeAndTargetIdOrderByIdAsc("task", 1L).size() >= 1);
        assertTrue(taskTriggerRepository.findBySourceTaskIdAndIsActiveTrue(1L).size() >= 1);
        assertTrue(versionRepository.findTopByTemplateIdOrderByVersionDesc(1L).isPresent());
    }
}
