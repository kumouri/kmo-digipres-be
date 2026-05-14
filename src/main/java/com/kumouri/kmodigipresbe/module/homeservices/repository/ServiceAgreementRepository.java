package com.kumouri.kmodigipresbe.module.homeservices.repository;

import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreement;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface ServiceAgreementRepository
        extends TenantScopedReactiveMongoRepository<ServiceAgreement, UUID> {
}
