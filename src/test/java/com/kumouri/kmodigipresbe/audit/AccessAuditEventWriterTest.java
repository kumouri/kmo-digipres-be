package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccessAuditEventWriter} — parsing + event-building + the
 * skip-when-unauthenticated rule. No Spring context / Mongo (runs in the fast CI gate).
 */
class AccessAuditEventWriterTest {

    private final AccessAuditEventRepository repository = mock(AccessAuditEventRepository.class);
    private final AccessAuditEventWriter writer = new AccessAuditEventWriter(repository);

    @Test
    void recordsAuthenticatedPointReadWithParsedResource() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/contacts/" + resourceId));
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        writer.record(exchange)
                .contextWrite(TenantContextHolder.write(new TenantContext(tenant, actor, Set.of("STAFF"))))
                .block();

        ArgumentCaptor<AccessAuditEvent> captor = ArgumentCaptor.forClass(AccessAuditEvent.class);
        verify(repository).save(captor.capture());
        AccessAuditEvent e = captor.getValue();
        assertThat(e.getId()).isNotNull();
        assertThat(e.getTenantId()).isEqualTo(tenant);
        assertThat(e.getActorUserId()).isEqualTo(actor);
        assertThat(e.getMethod()).isEqualTo("GET");
        assertThat(e.getPath()).isEqualTo("/contacts/" + resourceId);
        assertThat(e.getResourceType()).isEqualTo("contacts");
        assertThat(e.getResourceId()).isEqualTo(resourceId.toString());
        assertThat(e.getStatusCode()).isEqualTo(200);
        assertThat(e.getAt()).isNotNull();
    }

    @Test
    void collectionReadHasNullResourceId() {
        UUID tenant = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/deals"));
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        writer.record(exchange)
                .contextWrite(TenantContextHolder.write(new TenantContext(tenant, UUID.randomUUID(), Set.of())))
                .block();

        ArgumentCaptor<AccessAuditEvent> captor = ArgumentCaptor.forClass(AccessAuditEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getResourceType()).isEqualTo("deals");
        assertThat(captor.getValue().getResourceId()).isNull();
    }

    @Test
    void skipsWhenNoTenantContext() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/contacts/" + UUID.randomUUID()));
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        // No contextWrite -> no TenantContext -> nothing is persisted (unauthenticated).
        writer.record(exchange).block();

        verify(repository, never()).save(any());
    }
}
