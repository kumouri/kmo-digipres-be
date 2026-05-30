package com.kumouri.kmodigipresbe.module.fieldservice.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link WorkOrderNumberGenerator}: the {@code YYYY-MM-{seq:04}}
 * format + the empty-counter error path. The atomic {@code findAndModify} is
 * mocked — its atomicity is a MongoDB guarantee, not under test here.
 */
class WorkOrderNumberGeneratorTest {

    private final ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
    private final WorkOrderNumberGenerator generator = new WorkOrderNumberGenerator(mongo);

    private static String period() {
        LocalDate d = LocalDate.now(ZoneOffset.UTC);
        return String.format("%04d-%02d", d.getYear(), d.getMonthValue());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubSeq(Mono<Map> result) {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(Map.class), eq("work_order_number_counters")))
                .thenReturn((Mono) result);
    }

    @Test
    void formatsAsYearDashMonthDashFourDigitSeq() {
        stubSeq(Mono.just(Map.of("seq", 1)));
        StepVerifier.create(generator.next(UUID.randomUUID()))
                .expectNext(period() + "-0001")
                .verifyComplete();
    }

    @Test
    void padsSeqToFourDigits() {
        stubSeq(Mono.just(Map.of("seq", 42)));
        StepVerifier.create(generator.next(UUID.randomUUID()))
                .expectNext(period() + "-0042")
                .verifyComplete();
    }

    @Test
    void emptyCounterErrorsWith1332() {
        stubSeq(Mono.empty());
        StepVerifier.create(generator.next(UUID.randomUUID()))
                .expectError(DigiPresBeException.class)
                .verify();
    }
}
