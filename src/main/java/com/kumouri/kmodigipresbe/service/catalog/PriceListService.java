package com.kumouri.kmodigipresbe.service.catalog;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.catalog.PriceList;
import com.kumouri.kmodigipresbe.repository.PriceListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PriceListService {

    private final PriceListRepository priceLists;

    public Flux<PriceList> findAll() {
        return priceLists.findAll();
    }

    public Mono<PriceList> findById(UUID id) {
        return priceLists.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "PriceList not found", 2010, 404)));
    }

    public Mono<PriceList> create(PriceList toCreate) {
        toCreate.setId(null);
        return priceLists.save(toCreate);
    }

    public Mono<PriceList> update(UUID id, PriceList patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getName() != null) existing.setName(patch.getName());
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getCurrency() != null) existing.setCurrency(patch.getCurrency());
            if (patch.getEntries() != null) existing.setEntries(patch.getEntries());
            existing.setActive(patch.isActive());
            return priceLists.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return priceLists.deleteById(id);
    }
}
