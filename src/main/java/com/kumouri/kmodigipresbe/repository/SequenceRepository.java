package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.sequence.Sequence;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface SequenceRepository extends TenantScopedReactiveMongoRepository<Sequence, UUID> {
}
