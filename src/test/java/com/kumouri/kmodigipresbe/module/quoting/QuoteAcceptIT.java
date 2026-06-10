package com.kumouri.kmodigipresbe.module.quoting;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import com.kumouri.kmodigipresbe.module.quoting.repository.QuoteRequestRepository;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteBookingService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T8 — {@link QuoteBookingService}: the Q3 accept → booking-link SMS. Drives the service directly
 * (the accept controller is Q4). Proves: accept → one booking-link SMS (to the homeowner's phone,
 * carrying the per-tenant {@code bookingLink}) + status NEW→ACCEPTED + {@code acceptedAt}; a
 * re-accept sends ZERO second SMS (idempotent, explicit-boolean); a DECLINED quote → 4436.
 *
 * <p>§7: {@link TwilioSmsService} → {@code @MockitoBean} (no live send); sandbox token.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.quoting.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class QuoteAcceptIT {

    private static final String BOOKING_LINK = "https://comfort-air.test/book";
    private static final String HOMEOWNER_PHONE = "+13145551234";

    @Autowired QuoteBookingService bookingService;
    @Autowired QuoteRequestRepository quotes;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sent = new CopyOnWriteArrayList<>();

    private UUID tenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), QuoteRequest.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        sent.clear();

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("quote-accept-it-" + tenantId)
                .displayName("Quote Accept IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("quoting")).aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("twilio")
                .secrets(new HashMap<>(Map.of("authToken", "sandbox", "fromNumber", "+13145550000")))
                .config(new HashMap<>(Map.of("bookingLink", BOOKING_LINK)))
                .build())
                .contextWrite(TenantContextHolder.write(stamp()))
                .block();
    }

    private TenantContext stamp() {
        return new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
    }

    private QuoteRequest seedQuote(QuoteStatus status) {
        return mongo.save(QuoteRequest.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .contactPhone(HOMEOWNER_PHONE)
                .problemDescription("AC blowing warm")
                .status(status)
                .build()).block();
    }

    @Test
    void accept_sendsBookingLinkSms_andTransitionsToAccepted() {
        QuoteRequest q = seedQuote(QuoteStatus.NEW);

        QuoteRequest accepted = bookingService.accept(tenantId, q.getId())
                .contextWrite(TenantContextHolder.write(stamp()))
                .block();

        assertThat(accepted).isNotNull();
        assertThat(accepted.getStatus()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(accepted.getAcceptedAt()).isNotNull();
        assertThat(accepted.getBookingLinkSent()).isEqualTo(BOOKING_LINK);

        // Exactly one booking-link SMS to the homeowner carrying the link.
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).to().e164()).isEqualTo(HOMEOWNER_PHONE);
        assertThat(sent.get(0).body()).contains(BOOKING_LINK);

        // Persisted transition.
        QuoteRequest reloaded = mongo.findById(q.getId(), QuoteRequest.class).block();
        assertThat(reloaded.getStatus()).isEqualTo(QuoteStatus.ACCEPTED);
    }

    @Test
    void reAccept_isIdempotent_noSecondSms() {
        QuoteRequest q = seedQuote(QuoteStatus.NEW);

        bookingService.accept(tenantId, q.getId())
                .contextWrite(TenantContextHolder.write(stamp())).block();
        // Second accept of the now-ACCEPTED quote.
        QuoteRequest again = bookingService.accept(tenantId, q.getId())
                .contextWrite(TenantContextHolder.write(stamp())).block();

        assertThat(again.getStatus()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(sent).as("re-accept sends no second SMS").hasSize(1);
    }

    @Test
    void accept_declinedQuote_rejectsWith4436() {
        QuoteRequest q = seedQuote(QuoteStatus.DECLINED);

        assertThatThrownBy(() -> bookingService.accept(tenantId, q.getId())
                .contextWrite(TenantContextHolder.write(stamp())).block())
                .isInstanceOf(DigiPresBeException.class)
                .satisfies(ex -> assertThat(((DigiPresBeException) ex).getErrorCode()).isEqualTo(4436));

        assertThat(sent).isEmpty();
    }

    @Test
    void accept_missingQuote_404With4435() {
        assertThatThrownBy(() -> bookingService.accept(tenantId, UUID.randomUUID())
                .contextWrite(TenantContextHolder.write(stamp())).block())
                .isInstanceOf(DigiPresBeException.class)
                .satisfies(ex -> assertThat(((DigiPresBeException) ex).getErrorCode()).isEqualTo(4435));
    }

    @Test
    void accept_withoutContactPhone_stillTransitions_noSms() {
        QuoteRequest q = mongo.save(QuoteRequest.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .status(QuoteStatus.NEW)
                .build()).block();

        QuoteRequest accepted = bookingService.accept(tenantId, q.getId())
                .contextWrite(TenantContextHolder.write(stamp())).block();

        assertThat(accepted.getStatus()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(sent).as("no phone → no SMS, but the accept still durably transitions").isEmpty();
    }
}
