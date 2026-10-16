package com.familyapp.dailyoauth;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 仅允许脚本创建的随机回环测试库，继承完整 OAuth 流程。 */
@EnabledIfEnvironmentVariable(named="DAILY_OAUTH_POSTGRES_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/familyapp_oauth_it_[a-f0-9]+")
class OAuthPostgresTest extends OAuthFlowTest {
    @org.junit.jupiter.api.Test void rollback_refuses_data_then_empty_reverse_and_remigrate() throws Exception {
        String token=json.readTree(exchange(code(login()),verifier,200)).path("access_token").asText();
        String script=new String(new org.springframework.core.io.ClassPathResource("db/rollback/V1__OAuth_Storage.sql")
            .getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,()->connection.createStatement().execute(script));
            connection.rollback();
        }
        org.junit.jupiter.api.Assertions.assertTrue(json.readTree(introspect(token)).path("active").asBoolean());
        jdbc.update("DELETE FROM oauth2_authorization");jdbc.update("DELETE FROM oauth2_authorization_consent");
        try(var connection=jdbc.getDataSource().getConnection()){connection.createStatement().execute(script);}
        org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).load().migrate();
        new OAuthConfiguration().clients(jdbc,encoder,"test-chatgpt","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "https://client.example.test/callback","test-reader","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        org.junit.jupiter.api.Assertions.assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM oauth2_registered_client",Integer.class));
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("DAILY_OAUTH_POSTGRES_URL");
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/familyapp_oauth_it_[a-f0-9]+"))
            throw new IllegalArgumentException("仅允许隔离测试库");
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->"tester");
        registry.add("spring.datasource.password",()->System.getenv("DAILY_OAUTH_POSTGRES_PASSWORD"));
    }
}
