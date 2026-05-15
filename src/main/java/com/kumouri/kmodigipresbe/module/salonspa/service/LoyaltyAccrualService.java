package com.kumouri.kmodigipresbe.module.salonspa.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.module.salonspa.model.LoyaltyAccount;
import com.kumouri.kmodigipresbe.module.salonspa.model.LoyaltyTier;
import com.kumouri.kmodigipresbe.module.salonspa.model.LoyaltyTransaction;
import com.kumouri.kmodigipresbe.module.salonspa.repository.LoyaltyAccountRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.LoyaltyTransactionRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Subscribes to {@link DomainEventType#INVOICE_PAID} events and accrues
 * loyalty points for the associated contact. Tier promotions emit
 * {@link DomainEventType#LOYALTY_TIER_UPGRADED}.
 *
 * <p>Point values per visit:
 * <ul>
 *   <li>BRONZE — 10 pts</li>
 *   <li>SILVER — 15 pts</li>
 *   <li>GOLD   — 20 pts</li>
 * </ul>
 * Tier thresholds (cumulative visit count):
 * <ul>
 *   <li>BRONZE → SILVER at 10 visits</li>
 *   <li>SILVER → GOLD   at 25 visits</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class LoyaltyAccrualService {

    private static final int BRONZE_POINTS = 10;
    private static final int SILVER_POINTS = 15;
    private static final int GOLD_POINTS = 20;

    private static final int SILVER_THRESHOLD = 10;
    private static final int GOLD_THRESHOLD = 25;

    private final LoyaltyAccountRepository accounts;
    private final LoyaltyTransactionRepository transactions;
    private final DomainEventPublisher events;

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.INVOICE_PAID.equals(e.type()))
                .flatMap(e -> handleInvoicePaid(e)
                        .onErrorResume(err -> {
                            log.error("LoyaltyAccrualService: error processing INVOICE_PAID for tenant {}",
                                    e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private Mono<Void> handleInvoicePaid(DomainEvent event) {
        UUID tenantId = event.tenantId();
        UUID contactId = extractUuid(event.payload(), "contactId");
        UUID invoiceId = event.subjectId();
        if (contactId == null) {
            return Mono.empty();
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return findOrCreateAccount(tenantId, contactId)
                .flatMap(account -> accruePoints(account, invoiceId))
                .then()
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<LoyaltyAccount> findOrCreateAccount(UUID tenantId, UUID contactId) {
        return accounts.findByTenantIdAndContactId(tenantId, contactId)
                .switchIfEmpty(Mono.defer(() -> accounts.save(
                        LoyaltyAccount.builder()
                                .tenantId(tenantId)
                                .contactId(contactId)
                                .build())));
    }

    private Mono<LoyaltyAccount> accruePoints(LoyaltyAccount account, UUID invoiceId) {
        LoyaltyTier previousTier = account.getTier();
        int delta = pointsForTier(previousTier);

        account.setPoints(account.getPoints() + delta);
        account.setVisitCount(account.getVisitCount() + 1);
        account.setTier(tierForVisitCount(account.getVisitCount()));

        LoyaltyTransaction txn = LoyaltyTransaction.builder()
                .tenantId(account.getTenantId())
                .accountId(account.getId())
                .delta(delta)
                .reason("VISIT_COMPLETED")
                .invoiceId(invoiceId)
                .build();

        return accounts.save(account)
                .flatMap(saved -> transactions.save(txn).thenReturn(saved))
                .doOnNext(saved -> {
                    if (saved.getTier() != previousTier) {
                        emitTierUpgraded(saved, previousTier);
                    }
                });
    }

    private void emitTierUpgraded(LoyaltyAccount account, LoyaltyTier previousTier) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("accountId", account.getId());
        payload.put("contactId", account.getContactId());
        payload.put("previousTier", previousTier.name());
        payload.put("newTier", account.getTier().name());
        payload.put("visitCount", account.getVisitCount());
        events.publish(DomainEvent.of(
                DomainEventType.LOYALTY_TIER_UPGRADED,
                account.getTenantId(),
                account.getId(),
                payload));
    }

    private static int pointsForTier(LoyaltyTier tier) {
        return switch (tier) {
            case SILVER -> SILVER_POINTS;
            case GOLD -> GOLD_POINTS;
            default -> BRONZE_POINTS;
        };
    }

    private static LoyaltyTier tierForVisitCount(int visitCount) {
        if (visitCount >= GOLD_THRESHOLD) return LoyaltyTier.GOLD;
        if (visitCount >= SILVER_THRESHOLD) return LoyaltyTier.SILVER;
        return LoyaltyTier.BRONZE;
    }

    private static UUID extractUuid(Map<String, Object> payload, String key) {
        Object val = payload.get(key);
        if (val instanceof UUID uuid) return uuid;
        if (val instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
