package com.kumouri.kmodigipresbe.module.homeservices.widget;

import java.util.UUID;

/**
 * Phase 10e — public service-request widget submission response. The widget
 * snippet typically renders a "thanks, we received your request" page on
 * success; the IDs are returned so embedding code can deep-link a logged-in
 * staff handoff URL or attach them to a tracking cookie for the requester's
 * future visits.
 */
public record ServiceRequestSubmissionResponseDTO(UUID contactId, UUID workOrderId) {
}
