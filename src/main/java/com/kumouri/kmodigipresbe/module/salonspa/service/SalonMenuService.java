package com.kumouri.kmodigipresbe.module.salonspa.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RequiredArgsConstructor
public class SalonMenuService {

    private final ServiceMenuRepository menus;

    public Flux<ServiceMenu> findAll() {
        return menus.findAll();
    }

    public Mono<ServiceMenu> findById(UUID id) {
        return menus.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "ServiceMenu not found", 2900, 404)));
    }

    public Mono<ServiceMenuItem> findItem(UUID menuId, String itemId) {
        return findById(menuId).flatMap(menu -> menu.getServices().stream()
                .filter(s -> itemId.equals(s.getId()))
                .findFirst()
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new DigiPresBeException(
                        "ServiceMenuItem not found: " + itemId, 2900, 404))));
    }

    public Mono<ServiceMenu> create(ServiceMenu toCreate) {
        toCreate.setId(null);
        return menus.save(toCreate);
    }

    public Mono<ServiceMenu> update(UUID id, ServiceMenu patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getName() != null) existing.setName(patch.getName());
            if (patch.getServices() != null) existing.setServices(patch.getServices());
            return menus.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return menus.deleteById(id);
    }
}
