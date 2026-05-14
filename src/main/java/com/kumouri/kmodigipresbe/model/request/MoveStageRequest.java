package com.kumouri.kmodigipresbe.model.request;

import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MoveStageRequest {
    private PipelineStage stage;
    private String lostReason;
}
