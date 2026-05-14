package com.kumouri.kmodigipresbe.module.fieldservice.repository;

import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface JobSiteRepository extends TenantScopedReactiveMongoRepository<JobSite, UUID> {
}
