package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.contact.Company;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface CompanyRepository extends TenantScopedReactiveMongoRepository<Company, UUID> {
}
