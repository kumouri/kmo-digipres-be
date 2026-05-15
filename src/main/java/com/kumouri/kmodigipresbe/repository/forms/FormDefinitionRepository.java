package com.kumouri.kmodigipresbe.repository.forms;

import com.kumouri.kmodigipresbe.model.forms.FormDefinition;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface FormDefinitionRepository
        extends TenantScopedReactiveMongoRepository<FormDefinition, UUID> {
}
