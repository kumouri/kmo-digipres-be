package com.kumouri.kmodigipresbe.module.stylermatch;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.stylermatch.controller.StylerMatchIntakeController;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchService;
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
 * T12 — StylerMatchModuleGateIT: the default-OFF contract. StylerMatch rides the {@code chairfill}
 * salon-flagship key (its autoconfig is additionally {@code @ConditionalOnBean(SalonBookingService)}, so
 * a tenant must have both {@code chairfill} and {@code salon-spa} — the T9 StyleConsult posture). Proves
 * the realistic contract: with the salon flagship OFF the whole StylerMatch module is absent — no beans,
 * no controllers — so the public match route 404s (the {@code ServiceRequestWidgetController} "correct
 * outcome": disabled module → endpoint not registered → routing-level 404, not a 401).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.chairfill.enabled=false",
        "kmosf.modules.salon-spa.enabled=false",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class StylerMatchModuleGateIT {

    @Autowired WebTestClient web;
    @Autowired ApplicationContext ctx;

    @Test
    void disabledModule_publicMatchRouteIs404() {
        web.post().uri("/public/integrations/stylermatch/any-token/match")
                .bodyValue("{}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void disabledModule_noStylerMatchBeansRegistered() {
        assertThat(ctx.getBeanNamesForType(StylerMatchService.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(StylerMatchIntakeController.class)).isEmpty();
    }
}
