package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.reactivestreams.Publisher;
import org.springframework.data.mongodb.core.mapping.event.ReactiveAfterSaveCallback;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Evicts {@link ZitadelOrgTenantCache} whenever a {@link Tenant} is saved (Phase A2).
 *
 * <p>Wired exactly like {@code AuditingCallback}: implementing
 * {@link ReactiveAfterSaveCallback} as a Spring {@code @Component} auto-registers it
 * into the reactive Mongo entity-callback chain. Any tenant create/update — including
 * a {@code zitadelOrgId} reassignment — fires this and clears the whole one-entry-per-
 * tenant map, so the next request re-reads the authoritative mapping. Org reassignment
 * is rare and the map is tiny, so a full clear is the cheapest exact invalidation.
 *
 * <p>Returns the entity untouched; this callback never blocks or fails the save.
 */
@Component
@RequiredArgsConstructor
public class ZitadelOrgTenantCacheInvalidator implements ReactiveAfterSaveCallback<Tenant> {

    private final ZitadelOrgTenantCache cache;

    @Override
    @NonNull
    public Publisher<Tenant> onAfterSave(@NonNull Tenant entity,
                                         @NonNull Document target,
                                         @NonNull String collection) {
        cache.evictAll();
        return Mono.just(entity);
    }
}
