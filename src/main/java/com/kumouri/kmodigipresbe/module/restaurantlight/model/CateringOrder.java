package com.kumouri.kmodigipresbe.module.restaurantlight.model;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/**
 * A catering inquiry or confirmed catering engagement. Progresses through the
 * {@link CateringOrderStatus} lifecycle: INQUIRY → QUOTE_SENT → CONFIRMED →
 * COMPLETED (or CANCELLED at any pre-completion step).
 *
 * <p>The {@link #quoteId} is populated when {@code CateringOrderService.issueQuote}
 * creates a Phase-7 {@code Quote} from this order. Staff add line items to the
 * quote; acceptance triggers invoice creation (external to this module — Quote →
 * Invoice transition lives in the Phase-7 billing flow).
 */
@Document("restaurant_light_catering_orders")
@CompoundIndex(name = "tenant_status_idx", def = "{ 'tenantId': 1, 'status': 1 }")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CateringOrder implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;
    private UUID contactId;

    private LocalDate eventDate;
    private LocalTime eventTime;
    private int headcount;

    @Builder.Default
    private DeliveryMode deliveryMode = DeliveryMode.ON_SITE;

    private String dietaryRequirements;
    private String specialRequests;

    @Builder.Default
    private CateringOrderStatus status = CateringOrderStatus.INQUIRY;

    /** Set when {@code CateringOrderService.issueQuote} creates the Phase-7 Quote. */
    private UUID quoteId;

    /** Set when the Quote is accepted and converted to a Phase-7 Invoice. */
    private UUID invoiceId;

    @Builder.Default
    private Map<String, String> externalRefs = Map.of();

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getEntityType() {
        return "CATERING_ORDER";
    }
}
