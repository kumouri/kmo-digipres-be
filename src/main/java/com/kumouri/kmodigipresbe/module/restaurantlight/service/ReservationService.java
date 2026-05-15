package com.kumouri.kmodigipresbe.module.restaurantlight.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.Reservation;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.ReservationStatus;
import com.kumouri.kmodigipresbe.module.restaurantlight.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservations;
    private final DomainEventPublisher events;

    public Flux<Reservation> findAll() {
        return reservations.findAll();
    }

    public Mono<Reservation> findById(UUID id) {
        return reservations.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Reservation not found", 1421, 404)));
    }

    public Mono<Reservation> create(Reservation toCreate) {
        toCreate.setId(null);
        if (toCreate.getStatus() == null) toCreate.setStatus(ReservationStatus.PENDING);
        return reservations.save(toCreate).doOnSuccess(saved ->
                events.publish(DomainEvent.of(
                        DomainEventType.RESERVATION_CREATED,
                        saved.getTenantId(),
                        saved.getId(),
                        Map.of("contactId", String.valueOf(saved.getContactId()),
                                "partySize", saved.getPartySize()))));
    }

    public Mono<Reservation> update(UUID id, Reservation patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getContactId() != null) existing.setContactId(patch.getContactId());
            if (patch.getReservedAt() != null) existing.setReservedAt(patch.getReservedAt());
            if (patch.getPartySize() > 0) existing.setPartySize(patch.getPartySize());
            if (patch.getNotes() != null) existing.setNotes(patch.getNotes());
            if (patch.getExternalRef() != null) existing.setExternalRef(patch.getExternalRef());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return reservations.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return reservations.deleteById(id);
    }

    public Mono<Reservation> confirm(UUID id) {
        return transition(id, ReservationStatus.CONFIRMED,
                Set.of(ReservationStatus.PENDING));
    }

    public Mono<Reservation> seat(UUID id) {
        return transition(id, ReservationStatus.SEATED,
                Set.of(ReservationStatus.CONFIRMED));
    }

    public Mono<Reservation> complete(UUID id) {
        return transition(id, ReservationStatus.COMPLETED,
                Set.of(ReservationStatus.SEATED));
    }

    public Mono<Reservation> markNoShow(UUID id) {
        return transition(id, ReservationStatus.NO_SHOW,
                Set.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED));
    }

    public Mono<Reservation> cancel(UUID id) {
        return transition(id, ReservationStatus.CANCELLED,
                Set.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED));
    }

    private Mono<Reservation> transition(UUID id, ReservationStatus target,
                                         Set<ReservationStatus> validFrom) {
        return findById(id).flatMap(existing -> {
            if (!validFrom.contains(existing.getStatus())) {
                return Mono.error(new DigiPresBeException(
                        "Cannot transition reservation from " + existing.getStatus()
                                + " to " + target, 1422, 409));
            }
            existing.setStatus(target);
            return reservations.save(existing);
        });
    }
}
