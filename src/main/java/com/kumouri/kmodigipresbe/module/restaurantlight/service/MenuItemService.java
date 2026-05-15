package com.kumouri.kmodigipresbe.module.restaurantlight.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.MenuItem;
import com.kumouri.kmodigipresbe.module.restaurantlight.repository.MenuItemRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RequiredArgsConstructor
public class MenuItemService {

    private final MenuItemRepository items;

    public Flux<MenuItem> findAll() {
        return items.findAll();
    }

    public Mono<MenuItem> findById(UUID id) {
        return items.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "MenuItem not found", 1401, 404)));
    }

    public Mono<MenuItem> create(MenuItem toCreate) {
        toCreate.setId(null);
        return items.save(toCreate);
    }

    public Mono<MenuItem> update(UUID id, MenuItem patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getName() != null) existing.setName(patch.getName());
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getCourse() != null) existing.setCourse(patch.getCourse());
            if (patch.getUnitPriceCents() > 0) existing.setUnitPriceCents(patch.getUnitPriceCents());
            if (patch.getDietaryFlags() != null) existing.setDietaryFlags(patch.getDietaryFlags());
            if (patch.getModifiers() != null) existing.setModifiers(patch.getModifiers());
            existing.setAvailable(patch.isAvailable());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return items.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return items.deleteById(id);
    }
}
