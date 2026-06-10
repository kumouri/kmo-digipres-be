package com.kumouri.kmodigipresbe.module.techcopilot;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.techcopilot.controller.TechCopilotController;
import com.kumouri.kmodigipresbe.module.techcopilot.controller.TechDocController;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechCopilotService;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechDocService;
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
 * Tech Copilot (T13) — the default-OFF contract. With {@code kmosf.modules.techcopilot.enabled=false} the
 * whole module is absent — no beans, no controllers registered.
 *
 * <p>The staff routes are under the <em>authenticated</em> security path (not {@code /public/**}), so an
 * unauthenticated request is rejected with <strong>401</strong> by the {@code SecurityWebFilterChain}
 * <em>before</em> routing — i.e. the route never reaches a (non-existent) controller. The genuine
 * default-OFF proof for these authed routes is therefore the <strong>bean-absence</strong> assertion (the
 * {@code @ConditionalOnProperty}-gated controllers + services do not exist); the 401 confirms the wall is
 * intact. (Public-widget modules like {@code QuoteNowModuleGateIT} can assert a routing-level 404 because
 * their gated route is {@code permitAll}; T13 has no public route.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.techcopilot.enabled=false",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class TechCopilotModuleGateIT {

    @Autowired WebTestClient web;
    @Autowired ApplicationContext ctx;

    @Test
    void disabledModule_docsRouteIsWalledOff_401() {
        // Authed route + module off → 401 at the security wall, before routing reaches a (gone) controller.
        web.get().uri("/techcopilot/docs")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void disabledModule_askRouteIsWalledOff_401() {
        web.post().uri("/techcopilot/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"anything\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void disabledModule_noTechCopilotBeansRegistered() {
        assertThat(ctx.getBeanNamesForType(TechDocService.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(TechCopilotService.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(TechDocController.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(TechCopilotController.class)).isEmpty();
    }
}
