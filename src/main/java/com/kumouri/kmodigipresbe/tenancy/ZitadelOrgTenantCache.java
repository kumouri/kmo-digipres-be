package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Hand-rolled hot-path cache mapping a Zitadel organization id to the local
 * {@code Tenant} id (Phase A2).
 *
 * <p>Every Zitadel-mode request resolves its tenant from the
 * {@code urn:zitadel:iam:org:id} claim; that lookup is on the critical path of every
 * authenticated request, so it is cached in-process rather than hitting Mongo each
 * time. The map holds one entry per Zitadel-enabled tenant — tiny — so a full clear
 * on any {@code Tenant} write (see {@link ZitadelOrgTenantCacheInvalidator}) is cheap
 * and exact, with no staleness window. org→tenant is effectively immutable after
 * provisioning, so misses are rare after warm-up.
 *
 * <p>Deliberately hand-rolled (mirrors {@code AuditingCallback}'s {@code ConcurrentMap})
 * rather than introducing a Spring cache / Caffeine starter for a single one-entry-per-
 * tenant map.
 *
 * <p>{@link TenantRepository} is injected via {@link ObjectProvider} for the exact
 * reason {@code AuditEventWriter} does it: {@link ZitadelOrgTenantCacheInvalidator} is
 * a {@code ReactiveAfterSaveCallback} wired into the {@code reactiveMongoTemplate} /
 * {@code mappingMongoConverter} chain at context startup. A direct constructor
 * dependency on a {@code ReactiveMongoRepository} from this cache would loop
 * {@code callback → cache → repository → reactiveMongoTemplate → converter → callback}
 * and fail every {@code @SpringBootTest} context with
 * {@code BeanCurrentlyInCreationException}. Lazy resolution defers the repository
 * lookup until first request (long after startup), breaking the cycle.
 *
 * <p>A miss against Mongo (no tenant federates that org) raises
 * {@code DigiPresBeException(errorCode=3301, status=403)} — an authenticated Zitadel
 * principal for an org this deployment does not know.
 */
@Component
@RequiredArgsConstructor
public class ZitadelOrgTenantCache {

    private final ObjectProvider<TenantRepository> tenants;

    private final ConcurrentMap<String, UUID> orgToTenant = new ConcurrentHashMap<>();

    /**
     * Resolves the tenant id for a Zitadel org id, populating the cache on a miss.
     * Unknown org → {@code 3301} (403).
     */
    public Mono<UUID> resolve(String zitadelOrgId) {
        UUID cached = orgToTenant.get(zitadelOrgId);
        if (cached != null) {
            return Mono.just(cached);
        }
        return tenants.getObject().findByZitadelOrgId(zitadelOrgId)
                .map(tenant -> {
                    orgToTenant.put(zitadelOrgId, tenant.getId());
                    return tenant.getId();
                })
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Unknown Zitadel org: " + zitadelOrgId, 3301, 403)));
    }

    /**
     * Drops every cached mapping. Invoked on any {@code Tenant} save so an org
     * reassignment (rare) can never serve a stale tenant id.
     */
    public void evictAll() {
        orgToTenant.clear();
    }
}
