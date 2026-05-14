package com.kumouri.kmodigipresbe.tenancy;

import org.reactivestreams.Publisher;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.query.MongoEntityInformation;
import org.springframework.data.mongodb.repository.support.SimpleReactiveMongoRepository;
import org.springframework.lang.NonNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/**
 * Base repository implementation that auto-applies a tenantId predicate on reads,
 * existence checks, counts, and deletes when the entity implements {@link TenantScoped}.
 * Writes are stamped by {@link TenantStampingCallback}.
 *
 * Non-tenant-scoped entities (Tenant, User-lookup-by-email during login) pass through
 * to the default Spring Data behavior.
 */
public class TenantScopedSimpleReactiveMongoRepository<T, ID extends java.io.Serializable>
        extends SimpleReactiveMongoRepository<T, ID> {

    private final MongoEntityInformation<T, ID> entityInfo;
    private final ReactiveMongoOperations operations;
    private final boolean tenantScoped;

    public TenantScopedSimpleReactiveMongoRepository(
            MongoEntityInformation<T, ID> entityInformation,
            ReactiveMongoOperations operations) {
        super(entityInformation, operations);
        this.entityInfo = entityInformation;
        this.operations = operations;
        this.tenantScoped = TenantScoped.class.isAssignableFrom(entityInformation.getJavaType());
    }

    private <R> Mono<R> withTenant(Function<UUID, Mono<R>> work) {
        return TenantContextHolder.required().flatMap(ctx -> work.apply(ctx.tenantId()));
    }

    private <R> Flux<R> withTenantFlux(Function<UUID, Flux<R>> work) {
        return TenantContextHolder.required().flatMapMany(ctx -> work.apply(ctx.tenantId()));
    }

    private Criteria tenantCriteria(UUID tenantId) {
        return Criteria.where("tenantId").is(tenantId);
    }

    @Override
    @NonNull
    public Mono<T> findById(@NonNull ID id) {
        if (!tenantScoped) return super.findById(id);
        return withTenant(tenantId -> operations.findOne(
                new Query(Criteria.where("_id").is(id).andOperator(tenantCriteria(tenantId))),
                entityInfo.getJavaType()));
    }

    @Override
    @NonNull
    public Mono<T> findById(@NonNull Publisher<ID> publisher) {
        return Mono.from(publisher).flatMap(this::findById);
    }

    @Override
    @NonNull
    public Mono<Boolean> existsById(@NonNull ID id) {
        if (!tenantScoped) return super.existsById(id);
        return withTenant(tenantId -> operations.exists(
                new Query(Criteria.where("_id").is(id).andOperator(tenantCriteria(tenantId))),
                entityInfo.getJavaType()));
    }

    @Override
    @NonNull
    public Mono<Boolean> existsById(@NonNull Publisher<ID> publisher) {
        return Mono.from(publisher).flatMap(this::existsById);
    }

    @Override
    @NonNull
    public Flux<T> findAll() {
        if (!tenantScoped) return super.findAll();
        return withTenantFlux(tenantId -> operations.find(
                new Query(tenantCriteria(tenantId)), entityInfo.getJavaType()));
    }

    @Override
    @NonNull
    public Flux<T> findAllById(@NonNull Iterable<ID> ids) {
        if (!tenantScoped) return super.findAllById(ids);
        return withTenantFlux(tenantId -> operations.find(
                new Query(Criteria.where("_id").in(toList(ids)).andOperator(tenantCriteria(tenantId))),
                entityInfo.getJavaType()));
    }

    @Override
    @NonNull
    public Flux<T> findAllById(@NonNull Publisher<ID> idStream) {
        return Flux.from(idStream).collectList().flatMapMany(this::findAllById);
    }

    @Override
    @NonNull
    public Mono<Long> count() {
        if (!tenantScoped) return super.count();
        return withTenant(tenantId -> operations.count(
                new Query(tenantCriteria(tenantId)), entityInfo.getJavaType()));
    }

    @Override
    @NonNull
    public Mono<Void> deleteById(@NonNull ID id) {
        if (!tenantScoped) return super.deleteById(id);
        return withTenant(tenantId -> operations.remove(
                new Query(Criteria.where("_id").is(id).andOperator(tenantCriteria(tenantId))),
                entityInfo.getJavaType())).then();
    }

    @Override
    @NonNull
    public Mono<Void> deleteById(@NonNull Publisher<ID> publisher) {
        return Mono.from(publisher).flatMap(this::deleteById);
    }

    @Override
    @NonNull
    public Mono<Void> deleteAll() {
        if (!tenantScoped) return super.deleteAll();
        return withTenant(tenantId -> operations.remove(
                new Query(tenantCriteria(tenantId)), entityInfo.getJavaType())).then();
    }

    private static <X> java.util.List<X> toList(Iterable<X> it) {
        java.util.ArrayList<X> out = new java.util.ArrayList<>();
        it.forEach(out::add);
        return out;
    }
}
