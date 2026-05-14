package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Company;
import com.kumouri.kmodigipresbe.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyCrudService {

    private final CompanyRepository companies;

    public Flux<Company> findAll() {
        return companies.findAll();
    }

    public Mono<Company> findById(UUID id) {
        return companies.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Company not found", 1200, 404)));
    }

    public Mono<Company> create(Company toCreate) {
        toCreate.setId(null);
        return companies.save(toCreate);
    }

    public Mono<Company> update(UUID id, Company patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getName() != null) existing.setName(patch.getName());
            if (patch.getWebsite() != null) existing.setWebsite(patch.getWebsite());
            if (patch.getIndustry() != null) existing.setIndustry(patch.getIndustry());
            if (patch.getAddresses() != null) existing.setAddresses(patch.getAddresses());
            if (patch.getTags() != null) existing.setTags(patch.getTags());
            if (patch.getOwnerId() != null) existing.setOwnerId(patch.getOwnerId());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return companies.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return companies.deleteById(id);
    }
}
