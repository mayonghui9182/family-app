package com.familyapp.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * null 安全的 JsonNode &lt;-&gt; 字符串 JPA 转换器（审查修复 P1/I-2）。
 *
 * <p>存在理由：Hibernate 6 的 {@code @JdbcTypeCode(SqlTypes.JSON)} 映射在写侧会把
 * Java null 序列化成 JSON 文本 {@code 'null'}（4 字符字面量）而非 SQL NULL 落库。
 * calendar_events 的 chk_ce_date_recurrence 约束要求一次性日程
 * {@code event_date 非空 AND recurrence IS NULL}，{@code 'null'} 字面量在 PG 上
 * {@code IS NOT NULL} 为真，导致生产库上创建/更新一次性日程必然 CHECK 违例。
 *
 * <p>本转换器把列按普通字符串绑定，并保证双向 null 归一：
 * <ul>
 *   <li>写侧：Java null 与 NullNode 一律返回 null → JDBC 落为真正的 SQL NULL</li>
 *   <li>读侧：SQL NULL 返回 Java null（不再出现 NullNode），其余文本走 Jackson 解析</li>
 * </ul>
 *
 * <p>注意：转换器以 VARCHAR 参数绑定 JSON 文本，生产 PG 驱动默认会按 varchar 类型
 * 校验 jsonb 列（错误码 42804）。因此数据源 URL 必须带 {@code stringtype=unspecified}
 * （见 application.yml 与 docker-compose.yml），让服务端按目标列类型隐式转换。
 */
@Converter
public class NullSafeJsonNodeConverter implements AttributeConverter<JsonNode, String> {

    /** 转换器由 Hibernate 实例化（非 Spring Bean），使用无状态共享 ObjectMapper 即可 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(JsonNode attribute) {
        if (attribute == null || attribute.isNull()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("JsonNode 序列化失败，无法写入 JSON 列", e);
        }
    }

    @Override
    public JsonNode convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(dbData);
            // H2 测试栈兼容：H2 的「VARCHAR→JSON 隐式转换」是编码为 JSON 字符串标量语义
            //（非解析，项目内 JsonbMappingSmokeTest / application-test.yml 注释记录过同款坑），
            // 因此转换器写入的对象文本会被读回成多包一层引号的 TextNode。此处解包一层，
            // 使 H2 读回与生产 PG 等价。生产 PG（stringtype=unspecified 为解析语义）读回的
            // 本就是对象文本，isTextual 为 false，该分支不会触发。
            // 契约前提：本列只存对象/数组形重复规则（服务层 parseRecurrence 强制 isObject），
            // 不存在合法的「裸字符串」JSON 值，解包不会误伤真实数据形态。
            if (node != null && node.isTextual()) {
                node = MAPPER.readTree(node.textValue());
            }
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("JSON 列内容无法解析为 JsonNode: " + dbData, e);
        }
    }
}
