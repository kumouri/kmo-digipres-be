package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.imports.ImportJob;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface ImportJobRepository extends TenantScopedReactiveMongoRepository<ImportJob, UUID> {
}
