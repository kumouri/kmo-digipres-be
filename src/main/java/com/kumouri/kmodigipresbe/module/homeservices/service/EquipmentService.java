package com.kumouri.kmodigipresbe.module.homeservices.service;

import com.kumouri.kmodigipresbe.audit.AuditEventWriter;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.homeservices.model.Equipment;
import com.kumouri.kmodigipresbe.module.homeservices.repository.EquipmentRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD over {@link Equipment} plus a scheduled warranty-expiration scan that
 * emits {@link DomainEventType#EQUIPMENT_WARRANTY_EXPIRING} domain events for
 * any equipment whose warranty expires inside the next 30 days.
 *
 * <p>The scan is a tenant-fan-out system job (mirrors
 * {@code service/report/ReportScheduler} and {@code SequenceEngine}): it queries
 * across all tenants without a tenant context, then for each matched row
 * establishes a synthetic {@link TenantContext} via
 * {@link TenantContextHolder#write} so the published event carries the
 * equipment's own tenantId. {@code onErrorContinue} keeps a single bad row from
 * killing the whole tick.
 *
 * <p>Idempotency on the consumer side: warranty events are intentionally
 * re-emittable on every tick (the consuming Sequence engine debounces). A
 * {@code lastWarrantyEventAt} field on Equipment is deferred — revisit if
 * tenants report duplicate notifications.
 */
@Slf4j
@RequiredArgsConstructor
public class EquipmentService {

    /**
     * Synthetic role stamped on the {@link TenantContext} the warranty scan
     * publishes under. Mirrors {@code ReportScheduler.SYSTEM_ROLE}.
     */
    public static final String SYSTEM_ROLE = "WARRANTY_SCANNER";

    /**
     * Window (from {@code now}) the scan looks ahead. Equipment warrantied to
     * expire in less than this duration is flagged.
     */
    public static final Duration WARRANTY_WINDOW = Duration.ofDays(30);

    private final EquipmentRepository equipment;
    private final ReactiveMongoOperations mongo;
    private final AuditEventWriter auditor;
    private final DomainEventPublisher events;

    public Flux<Equipment> findAll() {
        return equipment.findAll();
    }

    public Mono<Equipment> findById(UUID id) {
        return equipment.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Equipment not found", 2730, 404)));
    }

    public Flux<Equipment> findByJobSite(UUID jobSiteId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> mongo.find(
                        new Query(Criteria.where("tenantId").is(ctx.tenantId())
                                .and("jobSiteId").is(jobSiteId)),
                        Equipment.class));
    }

    public Mono<Equipment> create(Equipment toCreate) {
        toCreate.setId(null);
        return equipment.save(toCreate);
    }

    public Mono<Equipment> update(UUID id, Equipment patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getJobSiteId() != null) existing.setJobSiteId(patch.getJobSiteId());
            if (patch.getEquipmentType() != null) existing.setEquipmentType(patch.getEquipmentType());
            if (patch.getManufacturer() != null) existing.setManufacturer(patch.getManufacturer());
            if (patch.getModel() != null) existing.setModel(patch.getModel());
            if (patch.getSerial() != null) existing.setSerial(patch.getSerial());
            if (patch.getInstallDate() != null) existing.setInstallDate(patch.getInstallDate());
            if (patch.getWarrantyExpiresAt() != null) existing.setWarrantyExpiresAt(patch.getWarrantyExpiresAt());
            if (patch.getLastServicedAt() != null) existing.setLastServicedAt(patch.getLastServicedAt());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return equipment.save(existing);
        });
    }

    /**
     * Mirrors {@code ContactCrudService.delete}: load under the current tenant
     * context so cross-tenant deletes short-circuit, audit explicitly (no reactive
     * delete callback in Spring Data MongoDB), then issue the actual delete.
     */
    public Mono<Void> delete(UUID id) {
        return findById(id)
                .flatMap(existing -> auditor.auditDelete(existing)
                        .then(equipment.deleteById(id)));
    }

    /**
     * Cron-driven warranty scan. Default fires at 09:00 server time daily; the
     * cron can be overridden via {@code kmosf.home-services.warranty-scan-cron}
     * for tenants that want a different window. (Spring's {@code @Scheduled}
     * does not allow {@code initialDelay} with a cron trigger — the previous
     * fire computation handles the equivalent automatically.)
     */
    @Scheduled(cron = "${kmosf.home-services.warranty-scan-cron:0 0 9 * * *}")
    public void tick() {
        scanWarranties()
                .onErrorContinue((err, evt) ->
                        log.warn("EquipmentService warranty scan dropped {}: {}", evt, err.toString()))
                .subscribe();
    }

    /**
     * Public for ITs — runs one scan synchronously when blocked. Reads
     * equipment across all tenants (system-level query, no tenant context),
     * then for each row establishes a synthetic context keyed to the
     * equipment's tenantId and emits one
     * {@link DomainEventType#EQUIPMENT_WARRANTY_EXPIRING} event.
     */
    public Mono<Void> scanWarranties() {
        Instant now = Instant.now();
        Instant horizon = now.plus(WARRANTY_WINDOW);
        Query q = new Query(Criteria.where("warrantyExpiresAt").gte(now).lte(horizon));
        return mongo.find(q, Equipment.class)
                .flatMap(this::emitWarrantyEvent)
                .onErrorContinue((err, row) -> log.warn(
                        "Warranty scan failed for row {}: {}", row, err.toString()))
                .then();
    }

    private Mono<Void> emitWarrantyEvent(Equipment eq) {
        TenantContext ctx = new TenantContext(eq.getTenantId(), null, Set.of(SYSTEM_ROLE));
        Map<String, Object> payload = new HashMap<>();
        payload.put("equipmentId", eq.getId());
        payload.put("jobSiteId", eq.getJobSiteId());
        payload.put("warrantyExpiresAt", eq.getWarrantyExpiresAt());
        payload.put("manufacturer", eq.getManufacturer());
        payload.put("model", eq.getModel());
        payload.put("serial", eq.getSerial());
        return Mono.fromRunnable(() -> events.publish(DomainEvent.of(
                        DomainEventType.EQUIPMENT_WARRANTY_EXPIRING,
                        eq.getTenantId(),
                        eq.getId(),
                        payload)))
                .then()
                .contextWrite(TenantContextHolder.write(ctx));
    }
}
