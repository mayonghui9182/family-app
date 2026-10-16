package com.familyapp.dailyoauth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import static org.junit.jupiter.api.Assertions.*;

/** 独立依赖兼容性预检；不启动现有应用，不连接业务数据库。 */
class FrameworkCompatibilityTest {
    @Test void pkce_consent_and_opaque_token_are_supported() {
        var settings=ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build();
        assertTrue(settings.isRequireProofKey());
        assertTrue(settings.isRequireAuthorizationConsent());
        assertEquals(OAuth2TokenFormat.REFERENCE,
                TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE).build().getAccessTokenFormat());
    }
}
