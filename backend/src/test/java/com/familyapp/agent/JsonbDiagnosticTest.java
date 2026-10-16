package com.familyapp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JsonbDiagnosticTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired RuleTemplateRepository repo;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @Test
    void diagnose() throws Exception {
        JsonNode conditions = MAPPER.readTree("{\"kind\":\"weekly\",\"weekdays\":[1,5]}");
        JsonNode content = MAPPER.readTree("{\"title\":\"t\",\"needs_ai\":false}");

        RuleTemplate tpl = new RuleTemplate();
        tpl.setFamilyId(9001L);
        tpl.setType("routine");
        tpl.setName("诊断");
        tpl.setConditions(conditions);
        tpl.setContent(content);
        repo.saveAndFlush(tpl);
        em.clear();

        // 绕过 Hibernate，直接看数据库里到底存了什么（应为 JSON 对象而非转义字符串文档）
        String raw = jdbc.queryForObject(
            "SELECT conditions FROM rule_templates WHERE family_id = 9001", String.class);
        System.out.println("RAW_FROM_DB=[" + raw + "]");

        RuleTemplate reloaded = repo.findById(tpl.getId()).orElseThrow();
        System.out.println("HIBERNATE_READ=[" + reloaded.getConditions() + "]");
        // JsonNode 映射修复验证：读回应与写入结构相等，而非 {"..."} 转义字符串文档
        assertEquals(conditions, reloaded.getConditions());
    }
}
