package com.kumouri.kmodigipresbe.module.salonspa.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.model.LoyaltyAccount;
import com.kumouri.kmodigipresbe.module.salonspa.model.LoyaltyTransaction;
import com.kumouri.kmodigipresbe.module.salonspa.repository.LoyaltyAccountRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.LoyaltyTransactionRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/salon-spa/loyalty-accounts")
@ConditionalOnProperty(prefix = "kmosf.modules.salon-spa", name = "enabled")
@RequiredArgsConstructor
public class LoyaltyController {

    private final LoyaltyAccountRepository accounts;
    private final LoyaltyTransactionRepository transactions;
    private final TenantModuleRegistry modules;

    @GetMapping("/{contactId}")
    public Mono<LoyaltyAccount> getAccount(@PathVariable UUID contactId) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> accounts.findByTenantIdAndContactId(ctx.tenantId(), contactId))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Loyalty account not found for contact " + contactId, 2920, 404)));
    }

    @GetMapping("/{contactId}/transactions")
    public Flux<LoyaltyTransaction> getTransactions(@PathVariable UUID contactId) {
        return guard().thenMany(TenantContextHolder.required()
                .flatMapMany(ctx -> accounts.findByTenantIdAndContactId(ctx.tenantId(), contactId)
                        .flatMapMany(account -> transactions
                                .findByTenantIdAndAccountIdOrderByCreatedAtDesc(
                                        ctx.tenantId(), account.getId()))
                        .switchIfEmpty(Flux.empty())));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(SalonSpaAutoConfiguration.MODULE_KEY);
    }
}
