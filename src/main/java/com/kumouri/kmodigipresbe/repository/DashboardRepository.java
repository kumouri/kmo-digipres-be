package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.report.Dashboard;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface DashboardRepository
        extends TenantScopedReactiveMongoRepository<Dashboard, UUID> {
}
