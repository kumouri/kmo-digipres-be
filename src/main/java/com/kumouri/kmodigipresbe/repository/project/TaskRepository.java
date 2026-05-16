package com.kumouri.kmodigipresbe.repository.project;

import com.kumouri.kmodigipresbe.model.project.Task;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TaskRepository extends TenantScopedReactiveMongoRepository<Task, UUID> {

    Flux<Task> findAllByTenantIdAndProjectIdOrderByOrderIndexAsc(UUID tenantId, UUID projectId);

    Flux<Task> findAllByTenantIdAndProjectIdAndStatus(UUID tenantId, UUID projectId, Task.TaskStatus status);

    Mono<Task> findByTenantIdAndId(UUID tenantId, UUID id);
}
