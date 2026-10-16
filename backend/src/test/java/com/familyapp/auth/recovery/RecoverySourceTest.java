package com.familyapp.auth.recovery;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class RecoverySourceTest {
    MockHttpServletRequest request(String peer, String... headers) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(peer);
        for (String header : headers) request.addHeader("X-Real-IP", header);
        return request;
    }
    @Test void directCallerCannotSpoofForwardedSource() {
        var source = new RecoverySource("192.0.2.10", "none");
        var request = request("198.51.100.3", "203.0.113.1");
        request.addHeader("X-Forwarded-For", "203.0.113.2");
        assertThat(source.resolve(request)).isEqualTo("198.51.100.3");
        assertThat(new RecoverySource("", "none").resolve(request("192.0.2.10", "203.0.113.1"))).isEqualTo("192.0.2.10");
    }
    @Test void trustedProxySeparatesClientsAndNormalizesIpv6() {
        var source = new RecoverySource("192.0.2.10,2001:db8::1", "none");
        assertThat(source.resolve(request("192.0.2.10", "203.0.113.1"))).isEqualTo("203.0.113.1");
        assertThat(source.resolve(request("192.0.2.10", "203.0.113.2"))).isEqualTo("203.0.113.2");
        assertThat(source.resolve(request("2001:db8:0:0:0:0:0:1", "2001:db8::2")))
                .isEqualTo(source.resolve(request("192.0.2.10", "2001:db8:0:0:0:0:0:2")));
    }
    @Test void invalidOrMultipleHeadersFallBackToPeer() {
        var source = new RecoverySource("192.0.2.10", "none");
        for (String header : java.util.List.of("example.test", "1.2.3.4, 5.6.7.8", "1.2.3.4:80", "999.1.2.3", "01.2.3.4", "::1%eth0", "")) {
            assertThat(source.resolve(request("192.0.2.10", header))).isEqualTo("192.0.2.10");
        }
        assertThat(source.resolve(request("192.0.2.10", "1.2.3.4", "5.6.7.8"))).isEqualTo("192.0.2.10");
        assertThat(source.resolve(request("192.0.2.10"))).isEqualTo("192.0.2.10");
    }
    @Test void globalForwardedHeaderRewritingIsRejected() {
        assertThatThrownBy(() -> new RecoverySource("192.0.2.10", "framework")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoverySource("192.0.2.10", "native")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidTrustConfigFailsInsteadOfBroadeningTrust() {
        for (String config : java.util.List.of("*", "0.0.0.0/0", "nginx", "192.0.2.10,")) {
            assertThatThrownBy(() -> new RecoverySource(config, "none")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
