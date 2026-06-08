package com.kumouri.kmodigipresbe.module.homeservices.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.controller.dto.MissedCallInboxItemDTO;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;

/**
 * The Home Services "Missed-Call Inbox" read endpoint (HS-1 — "Front Desk That Never Sleeps"). The
 * dispatch board is {@code scheduledStart}-gated, so a voicemail-sourced DRAFT {@link WorkOrder}
 * (which has no {@code scheduledStart}) is invisible there by design and would be lost to the UI
 * without this status-filtered queue (plan §6 dispatch-board coupling). HS-4's admin UI consumes
 * this contract; the {@code Schedule} action there sets {@code scheduledStart}+technician and
 * promotes the WO onto the dated board.
 *
 * <p>Returns the tenant's DRAFT WorkOrders that originated from a voicemail (those whose
 * {@code customFields.callSid} is set — the {@code MultiTradeExtractionStrategy} marker), newest
 * first. Gated like the other home-services staff controllers:
 * {@code @ConditionalOnProperty(home-services)} on the bean + a per-request
 * {@link TenantModuleRegistry#requireEnabled} check (errorCode {@code 1132}/404 when the module is
 * loaded but not enabled for the tenant — the {@code 4202} "inbox read requested but home-services
 * not enabled" posture, surfaced via the shared module-gate code). Staff-only by the security chain
 * (the {@code /home-services/**} routes are authenticated, like {@code DispatchBoardController}).
 */
@RestController
@RequestMapping("/home-services/missed-call-inbox")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class MissedCallInboxController {

    private final WorkOrderRepository workOrders;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<MissedCallInboxItemDTO> list() {
        return guard()
                .thenMany(TenantContextHolder.required()
                        .flatMapMany(ctx -> workOrders.findAllByTenantIdAndStatus(
                                ctx.tenantId(), WorkOrderStatus.DRAFT)))
                .filter(MissedCallInboxController::isVoicemailSourced)
                .sort(Comparator.comparing(
                        WorkOrder::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(MissedCallInboxItemDTO::from);
    }

    /** A DRAFT WorkOrder is voicemail-sourced iff its customFields carry a non-blank callSid. */
    private static boolean isVoicemailSourced(WorkOrder wo) {
        Map<String, Object> cf = wo.getCustomFields();
        if (cf == null) return false;
        Object callSid = cf.get("callSid");
        return callSid != null && !callSid.toString().isBlank();
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(HomeServicesAutoConfiguration.MODULE_KEY);
    }
}
