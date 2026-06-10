package com.kumouri.kmodigipresbe.module.quoting;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8 — QuoteNowModuleGateIT: the default-OFF contract. With {@code kmosf.modules.quoting.enabled=false}
 * the whole module is absent — no beans, no controllers registered — so the public intake route 404s
 * (the {@code ServiceRequestWidgetController} "correct outcome": disabled module → endpoint not
 * registered → routing-level 404, not a 401). Confirms the module is genuinely off by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.quoting.enabled=false",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class QuoteNowModuleGateIT {

    @Autowired WebTestClient web;
    @Autowired ApplicationContext ctx;

    @Test
    void disabledModule_publicIntakeRouteIs404() {
        web.post().uri("/public/integrations/quoting/any-token/quote")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void disabledModule_noQuotingBeansRegistered() {
        // The whole quoting module is absent — none of its beans exist.
        assertThat(ctx.getBeanNamesForType(
                com.kumouri.kmodigipresbe.module.quoting.service.QuoteSynthesisService.class))
                .isEmpty();
        assertThat(ctx.getBeanNamesForType(
                com.kumouri.kmodigipresbe.module.quoting.controller.QuoteIntakeController.class))
                .isEmpty();
        assertThat(ctx.getBeanNamesForType(
                com.kumouri.kmodigipresbe.module.quoting.controller.QuoteInboxController.class))
                .isEmpty();
    }
}
