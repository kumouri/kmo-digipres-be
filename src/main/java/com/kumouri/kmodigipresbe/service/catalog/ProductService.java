package com.kumouri.kmodigipresbe.service.catalog;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.catalog.Product;
import com.kumouri.kmodigipresbe.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository products;

    public Flux<Product> findAll() {
        return products.findAll();
    }

    public Mono<Product> findById(UUID id) {
        return products.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Product not found", 2000, 404)));
    }

    public Mono<Product> create(Product toCreate) {
        toCreate.setId(null);
        return products.save(toCreate);
    }

    public Mono<Product> update(UUID id, Product patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getSku() != null) existing.setSku(patch.getSku());
            if (patch.getName() != null) existing.setName(patch.getName());
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getUnitPrice() != null) existing.setUnitPrice(patch.getUnitPrice());
            if (patch.getCurrency() != null) existing.setCurrency(patch.getCurrency());
            if (patch.getUnitOfMeasure() != null) existing.setUnitOfMeasure(patch.getUnitOfMeasure());
            if (patch.getType() != null) existing.setType(patch.getType());
            existing.setActive(patch.isActive());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return products.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return products.deleteById(id);
    }
}
