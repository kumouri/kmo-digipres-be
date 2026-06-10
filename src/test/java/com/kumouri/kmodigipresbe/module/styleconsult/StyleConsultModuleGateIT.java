package com.kumouri.kmodigipresbe.module.styleconsult;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.styleconsult.controller.StyleConsultIntakeController;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultService;
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
 * T9 — StyleConsultModuleGateIT: the default-OFF contract. StyleConsult rides the {@code chairfill}
 * salon-flagship key (its autoconfig is additionally {@code @ConditionalOnBean(SalonBookingService)},
 * so a tenant must have both {@code chairfill} and {@code salon-spa} — the ReviewBoost posture; a
 * chairfill-without-salon-spa server is not a supported configuration for any ChairFill-family module).
 * Proves the realistic contract: with the salon flagship OFF the whole StyleConsult module is absent —
 * no beans, no controllers — so the public intake route 404s (the {@code ServiceRequestWidgetController}
 * "correct outcome": disabled module → endpoint not registered → routing-level 404, not a 401).
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
class StyleConsultModuleGateIT {

    @Autowired WebTestClient web;
    @Autowired ApplicationContext ctx;

    @Test
    void disabledModule_publicIntakeRouteIs404() {
        web.post().uri("/public/integrations/styleconsult/any-token/consult")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void disabledModule_noStyleConsultBeansRegistered() {
        assertThat(ctx.getBeanNamesForType(StyleConsultService.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(StyleConsultIntakeController.class)).isEmpty();
    }
}
