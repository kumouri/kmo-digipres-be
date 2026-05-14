package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.billing.Payment;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface PaymentRepository extends TenantScopedReactiveMongoRepository<Payment, UUID> {
    Flux<Payment> findAllByTenantIdAndInvoiceId(UUID tenantId, UUID invoiceId);
}
