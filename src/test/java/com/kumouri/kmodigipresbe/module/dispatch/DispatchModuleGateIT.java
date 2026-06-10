package com.kumouri.kmodigipresbe.module.dispatch;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.dispatch.controller.DispatchController;
import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchAnalyticsService;
import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchOptimizerService;
import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T14 — the default-OFF contract. With {@code kmosf.modules.dispatch.enabled=false} the whole module is
 * absent — no beans, no controller registered. The dispatcher routes are under the <em>authenticated</em>
 * security path (not {@code /public/**}), so an unauthenticated request is rejected with <strong>401</strong>
 * by the security chain <em>before</em> routing; the genuine default-OFF proof is the bean-absence assertion
 * (the {@code TechCopilotModuleGateIT} precedent — DispatchIQ has no public route).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.dispatch.enabled=false",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class DispatchModuleGateIT {

    @Autowired WebTestClient web;
    @Autowired ApplicationContext ctx;

    @Test
    void disabledModule_optimizeRouteIsWalledOff_401() {
        web.get().uri("/dispatch/optimize?date=2026-07-01")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void disabledModule_applyRouteIsWalledOff_401() {
        web.post().uri("/dispatch/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"assignments\":[]}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void disabledModule_noDispatchBeansRegistered() {
        assertThat(ctx.getBeanNamesForType(DispatchOptimizerService.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(DispatchPlanService.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(DispatchAnalyticsService.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(DispatchController.class)).isEmpty();
    }
}
