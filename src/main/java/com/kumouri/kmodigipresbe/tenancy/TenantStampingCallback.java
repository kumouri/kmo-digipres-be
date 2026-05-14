package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.springframework.data.mongodb.core.mapping.event.ReactiveBeforeConvertCallback;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class TenantStampingCallback implements ReactiveBeforeConvertCallback<TenantScoped> {

    @Override
    @NonNull
    public Mono<TenantScoped> onBeforeConvert(@NonNull TenantScoped entity, @NonNull String collection) {
        return TenantContextHolder.current()
                .map(tenant -> {
                    if (entity.getTenantId() == null) {
                        entity.setTenantId(tenant.tenantId());
                    } else if (!entity.getTenantId().equals(tenant.tenantId())) {
                        throw new DigiPresBeException(
                                "Refusing to save entity with foreign tenantId", 1003, 403);
                    }
                    return entity;
                })
                .switchIfEmpty(Mono.defer(() -> {
                    if (entity.getTenantId() != null) {
                        return Mono.just(entity);
                    }
                    return Mono.error(new DigiPresBeException(
                            "Cannot persist tenant-scoped entity outside tenant context",
                            1004, 403));
                }));
    }
}
