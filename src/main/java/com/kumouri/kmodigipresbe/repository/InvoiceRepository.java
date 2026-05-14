package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface InvoiceRepository extends TenantScopedReactiveMongoRepository<Invoice, UUID> {
}
