package com.kumouri.kmodigipresbe.controller.contractor;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.response.PayoutReport;
import com.kumouri.kmodigipresbe.service.contractor.PayoutReportService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin payout + margin reporting surface (Phase J — J4) — the 1099 payout view. All
 * ADMIN-gated via {@code RoleGuard.requireRole("ADMIN")} (a non-admin → {@code 1800}/403).
 * Pure read: returns the {@link PayoutReport} projection (what is owed a contractor =
 * approved hours × cost rate, plus margin = bill − payout), per {@code Timesheet} period and
 * as a window total / year-to-date. Only APPROVED time counts (the J3 invoicing gate);
 * entries with a null cost rate are surfaced via {@code hasUnratedEntries}, never zeroed.
 *
 * <p>Request validation: {@code userId} is required and {@code to} must be after {@code from}
 * ({@code 4160}/400 otherwise). Module-gated via {@code kmosf.modules.contractor.enabled}
 * (the J1/J2/J3 controller precedent).
 */
@RestController
@RequestMapping("/reports/payout")
@ConditionalOnProperty(prefix = "kmosf.modules.contractor", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutReportService service;

    /**
     * {@code GET /reports/payout?userId=&from=&to=} — payout + margin for one contractor over
     * the explicit {@code [from, to]} window.
     */
    @GetMapping
    public Mono<PayoutReport> payout(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return RoleGuard.requireRole("ADMIN")
                .then(Mono.defer(() -> {
                    if (userId == null) {
                        return Mono.error(new DigiPresBeException(
                                "userId is required", 4160, 400));
                    }
                    if (from == null || to == null) {
                        return Mono.error(new DigiPresBeException(
                                "from and to are required", 4160, 400));
                    }
                    if (!to.isAfter(from)) {
                        return Mono.error(new DigiPresBeException(
                                "to must be after from", 4160, 400));
                    }
                    return service.payout(userId, from, to);
                }));
    }

    /**
     * {@code GET /reports/payout/ytd?userId=&year=} — year-to-date payout + margin for one
     * contractor. The current year runs Jan 1 → now; a prior year runs the full calendar year
     * (UTC) — see {@code PayoutReportService.payoutYtd}.
     */
    @GetMapping("/ytd")
    public Mono<PayoutReport> payoutYtd(
            @RequestParam(required = false) UUID userId,
            @RequestParam int year) {
        return RoleGuard.requireRole("ADMIN")
                .then(Mono.defer(() -> {
                    if (userId == null) {
                        return Mono.error(new DigiPresBeException(
                                "userId is required", 4160, 400));
                    }
                    return service.payoutYtd(userId, year);
                }));
    }
}
