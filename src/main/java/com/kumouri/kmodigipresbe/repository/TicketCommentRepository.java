package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.servicehub.TicketComment;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface TicketCommentRepository extends ReactiveMongoRepository<TicketComment, UUID> {

    Flux<TicketComment> findAllByTenantIdAndTicketIdOrderByCreatedAtAsc(UUID tenantId, UUID ticketId);
}
