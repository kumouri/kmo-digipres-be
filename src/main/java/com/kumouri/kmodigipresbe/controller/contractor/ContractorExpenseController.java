package com.kumouri.kmodigipresbe.controller.contractor;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.timetracking.Expense;
import com.kumouri.kmodigipresbe.service.contractor.ContractorSelfResolver;
import com.kumouri.kmodigipresbe.service.timetracking.ExpenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Contractor-scoped expense surface (Phase J — J2). A contractor reads and submits ONLY
 * their own expenses — the self user id is resolved from the token via
 * {@link ContractorSelfResolver}, never taken from the request.
 *
 * <p>Submission is allowed (a contractor incurs reimbursable costs); a body carrying a
 * foreign {@code userId} ({@code != self}) is rejected with {@code 4134}/400 before any
 * write (a null {@code userId} is stamped as self by the service). The expense lands
 * {@code PENDING} — <strong>approval is NOT exposed here</strong> (that stays the ADMIN-gated
 * {@code POST /expenses/{id}/approve}); a contractor cannot approve their own (or anyone's)
 * expense.
 *
 * <p>Module-gated via {@code kmosf.modules.contractor.enabled} (on by default).
 */
@RestController
@RequestMapping("/me/contractor/expenses")
@ConditionalOnProperty(prefix = "kmosf.modules.contractor", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ContractorExpenseController {

    private final ContractorSelfResolver self;
    private final ExpenseService service;

    /** Lists the caller's own expenses (self forced). */
    @GetMapping
    public Flux<Expense> list() {
        return self.resolveUserId().flatMapMany(service::findByUser);
    }

    /**
     * Submits an expense as self. A body {@code userId} that is non-null and {@code != self}
     * is rejected with {@code 4134}/400 (cross-user write); a null {@code userId} is stamped
     * as self by the service. The expense is created {@code PENDING}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Expense> submit(@RequestBody Expense body) {
        return self.resolveUserId().flatMap(uid -> {
            if (body.getUserId() != null && !body.getUserId().equals(uid)) {
                return Mono.error(new DigiPresBeException(
                        "A contractor may only submit expenses for themselves", 4134, 400));
            }
            body.setUserId(uid);
            return service.create(body);
        });
    }
}
