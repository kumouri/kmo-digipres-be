package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the read-access audit trail round-trips to MongoDB: {@link AccessAuditEventWriter}
 * persists a well-formed {@link AccessAuditEvent} (document mapping + TTL/compound indexes
 * create cleanly; the {@code TenantStampingCallback} accepts the in-context tenant), queryable
 * via the tenant-scoped derived finder. Exercises the writer directly — the filter only adds
 * the post-chain hook (unit-tested in {@code AccessAuditWebFilterTest}) — so no JWT/HTTP setup
 * is needed.
 *
 * <p>A fresh random tenant per run isolates this test on the shared Testcontainers Mongo, so
 * no cleanup is needed (and the inherited {@code deleteAll()} would require a tenant context
 * anyway — the derived finder used here does not).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccessAuditPersistenceIT {

    @Autowired
    private AccessAuditEventWriter writer;

    @Autowired
    private AccessAuditEventRepository repository;

    @Test
    void recordsAuthenticatedAccessToMongo() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        TenantContext ctx = new TenantContext(tenant, actor, Set.of("STAFF"));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/contacts/" + resourceId));
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        // Save runs inside the request's tenant context, exactly as AccessAuditWebFilter
        // invokes it at runtime (TenantWebFilter having written the context).
        writer.record(exchange)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        List<AccessAuditEvent> events = repository
                .findAllByTenantIdAndActorUserIdOrderByAtDesc(tenant, actor)
                .collectList()
                .block();

        assertThat(events).hasSize(1);
        AccessAuditEvent e = events.get(0);
        assertThat(e.getId()).isNotNull();
        assertThat(e.getMethod()).isEqualTo("GET");
        assertThat(e.getPath()).isEqualTo("/contacts/" + resourceId);
        assertThat(e.getResourceType()).isEqualTo("contacts");
        assertThat(e.getResourceId()).isEqualTo(resourceId.toString());
        assertThat(e.getStatusCode()).isEqualTo(200);
        assertThat(e.getTenantId()).isEqualTo(tenant);
        assertThat(e.getActorUserId()).isEqualTo(actor);
        assertThat(e.getAt()).isNotNull();
    }
}
