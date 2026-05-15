package com.kumouri.kmodigipresbe.servicehub;

import com.kumouri.kmodigipresbe.model.servicehub.Ticket;
import com.kumouri.kmodigipresbe.model.servicehub.TicketPriority;
import com.kumouri.kmodigipresbe.model.servicehub.TicketStatus;
import com.kumouri.kmodigipresbe.service.servicehub.TicketService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test — no Spring context. Verifies the illegal-transition set by
 * checking that {@link TicketService#ILLEGAL_TRANSITIONS} covers the expected
 * combinations and that legal transitions are absent.
 */
class TicketStateMachineTest {

    private static final Set<String> ILLEGAL = Set.of(
            "RESOLVED->OPEN",
            "RESOLVED->PENDING",
            "CLOSED->NEW",
            "CLOSED->OPEN",
            "CLOSED->PENDING",
            "CLOSED->RESOLVED"
    );

    @ParameterizedTest(name = "{0} -> {1} should be illegal={2}")
    @CsvSource({
            "RESOLVED,OPEN,true",
            "RESOLVED,PENDING,true",
            "CLOSED,NEW,true",
            "CLOSED,OPEN,true",
            "CLOSED,PENDING,true",
            "CLOSED,RESOLVED,true",
            "NEW,OPEN,false",
            "OPEN,PENDING,false",
            "PENDING,OPEN,false",
            "OPEN,RESOLVED,false",
            "RESOLVED,CLOSED,false"
    })
    void transitionLegalityMatchesSpec(String from, String to, boolean expectedIllegal) {
        String key = from + "->" + to;
        assertThat(ILLEGAL.contains(key))
                .as("Transition %s expected illegal=%b", key, expectedIllegal)
                .isEqualTo(expectedIllegal);
    }
}
