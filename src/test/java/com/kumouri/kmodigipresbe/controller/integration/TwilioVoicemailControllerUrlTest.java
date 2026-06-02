package com.kumouri.kmodigipresbe.controller.integration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link TwilioVoicemailController#reconstructFullUrl} (Phase 1). Verifies the
 * proxy-aware URL reconstruction the Twilio signature is computed over — deterministically,
 * with no Spring context / Docker. Pins the exact URL shape {@code TwilioVoicemailIT} signs
 * against (forwarded host + the full {@code /api/v1/...} raw path), so a regression in either
 * the controller or the IT signing helper surfaces here.
 */
class TwilioVoicemailControllerUrlTest {

    @Test
    void forwardedHeaders_preferredOverRequestUri() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("http://internal-host:8080/api/v1/public/integrations/twilio/abc/voicemail")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "api-demo.kmosolutionsfoundry.test")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        assertThat(TwilioVoicemailController.reconstructFullUrl(exchange))
                .isEqualTo("https://api-demo.kmosolutionsfoundry.test"
                        + "/api/v1/public/integrations/twilio/abc/voicemail");
    }

    @Test
    void forwardedHostList_usesFirstValue() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("http://internal-host:8080/api/v1/public/integrations/twilio/abc/voicemail")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "api-demo.kmosolutionsfoundry.test, internal-proxy")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        assertThat(TwilioVoicemailController.reconstructFullUrl(exchange))
                .isEqualTo("https://api-demo.kmosolutionsfoundry.test"
                        + "/api/v1/public/integrations/twilio/abc/voicemail");
    }

    @Test
    void noForwardedHeaders_fallsBackToRequestUri() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("https://direct-host/api/v1/public/integrations/twilio/abc/voicemail")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        assertThat(TwilioVoicemailController.reconstructFullUrl(exchange))
                .isEqualTo("https://direct-host/api/v1/public/integrations/twilio/abc/voicemail");
    }

    @Test
    void queryString_preserved() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("http://internal-host:8080/api/v1/public/integrations/twilio/abc/voice?x=1")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "api-demo.test")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        assertThat(TwilioVoicemailController.reconstructFullUrl(exchange))
                .isEqualTo("https://api-demo.test/api/v1/public/integrations/twilio/abc/voice?x=1");
    }
}
