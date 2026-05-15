package com.kumouri.kmodigipresbe.module.salonspa.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RequiredArgsConstructor
public class StaffMemberService {

    private final StaffMemberRepository staff;

    public Flux<StaffMember> findAll() {
        return staff.findAll();
    }

    public Flux<StaffMember> findActive() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> staff.findByTenantIdAndActive(ctx.tenantId(), true));
    }

    public Mono<StaffMember> findById(UUID id) {
        return staff.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "StaffMember not found", 2900, 404)));
    }

    public Mono<StaffMember> create(StaffMember toCreate) {
        toCreate.setId(null);
        toCreate.setActive(true);
        return staff.save(toCreate);
    }

    public Mono<StaffMember> update(UUID id, StaffMember patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getDisplayName() != null) existing.setDisplayName(patch.getDisplayName());
            if (patch.getUserId() != null) existing.setUserId(patch.getUserId());
            if (patch.getEligibleServiceIds() != null) existing.setEligibleServiceIds(patch.getEligibleServiceIds());
            if (patch.getAvailabilityWindows() != null) existing.setAvailabilityWindows(patch.getAvailabilityWindows());
            existing.setActive(patch.isActive());
            return staff.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(existing -> {
            existing.setActive(false);
            return staff.save(existing).then();
        });
    }
}
