package com.kumouri.kmodigipresbe.model.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DashboardItem {
    private UUID savedReportId;
    private int gridX;
    private int gridY;
    private int gridW;
    private int gridH;
}
