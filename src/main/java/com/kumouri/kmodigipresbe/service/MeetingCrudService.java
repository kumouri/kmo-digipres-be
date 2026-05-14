package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import com.kumouri.kmodigipresbe.repository.MeetingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MeetingCrudService {

    private final MeetingRepository meetings;

    public Flux<Meeting> findAll() {
        return meetings.findAll();
    }

    public Mono<Meeting> findById(UUID id) {
        return meetings.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Meeting not found", 1500, 404)));
    }

    public Mono<Meeting> create(Meeting toCreate) {
        toCreate.setId(null);
        return meetings.save(toCreate);
    }

    public Mono<Meeting> update(UUID id, Meeting patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getName() != null) existing.setName(patch.getName());
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getLocation() != null) existing.setLocation(patch.getLocation());
            if (patch.getStart() != null) existing.setStart(patch.getStart());
            if (patch.getEnd() != null) existing.setEnd(patch.getEnd());
            existing.setAllDay(patch.isAllDay());
            if (patch.getOrganizerContactId() != null) existing.setOrganizerContactId(patch.getOrganizerContactId());
            if (patch.getAttendeeContactIds() != null) existing.setAttendeeContactIds(patch.getAttendeeContactIds());
            return meetings.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return meetings.deleteById(id);
    }
}
