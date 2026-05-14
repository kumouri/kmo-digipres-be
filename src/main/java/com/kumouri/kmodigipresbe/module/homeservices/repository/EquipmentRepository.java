package com.kumouri.kmodigipresbe.module.homeservices.repository;

import com.kumouri.kmodigipresbe.module.homeservices.model.Equipment;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface EquipmentRepository extends TenantScopedReactiveMongoRepository<Equipment, UUID> {
}
