package com.kumouri.kmodigipresbe.model.request;

import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DealDTO {
    private UUID id;
    private String title;
    private PipelineStage stage;
    private BigDecimal value;
    private String currency;
    private LocalDate expectedCloseDate;
    private UUID primaryContactId;
    private UUID companyId;
    private UUID ownerId;
    private String lostReason;
    private Map<String, Object> customFields;
}
