package com.kumouri.kmodigipresbe.module.fieldservice.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.JobSiteRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.NearQuery;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RequiredArgsConstructor
public class JobSiteService {

    private final JobSiteRepository jobSites;
    private final ReactiveMongoOperations mongo;

    public Flux<JobSite> findAll() {
        return jobSites.findAll();
    }

    public Mono<JobSite> findById(UUID id) {
        return jobSites.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "JobSite not found", 1320, 404)));
    }

    public Mono<JobSite> create(JobSite toCreate) {
        toCreate.setId(null);
        return jobSites.save(toCreate);
    }

    public Mono<JobSite> update(UUID id, JobSite patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getContactId() != null) existing.setContactId(patch.getContactId());
            if (patch.getCompanyId() != null) existing.setCompanyId(patch.getCompanyId());
            if (patch.getAddress() != null) existing.setAddress(patch.getAddress());
            if (patch.getLocation() != null) existing.setLocation(patch.getLocation());
            if (patch.getLabel() != null) existing.setLabel(patch.getLabel());
            if (patch.getAccessNotes() != null) existing.setAccessNotes(patch.getAccessNotes());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return jobSites.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return jobSites.deleteById(id);
    }

    /**
     * {@code $near} against the {@code 2dsphere} index. {@code radiusKm} bounds the
     * search radius; results are ordered by distance ascending.
     */
    public Flux<JobSite> near(double lat, double lng, double radiusKm) {
        return TenantContextHolder.required().flatMapMany(ctx -> {
            NearQuery near = NearQuery.near(new Point(lng, lat), Metrics.KILOMETERS)
                    .maxDistance(new Distance(radiusKm, Metrics.KILOMETERS))
                    .spherical(true)
                    .query(new Query(Criteria.where("tenantId").is(ctx.tenantId())));
            return mongo.geoNear(near, JobSite.class).map(geo -> geo.getContent());
        });
    }
}
