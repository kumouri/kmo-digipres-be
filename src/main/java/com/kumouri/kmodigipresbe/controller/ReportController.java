package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.service.reports.ActivityReportService;
import com.kumouri.kmodigipresbe.service.reports.PipelineReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final PipelineReportService pipeline;
    private final ActivityReportService activity;

    @GetMapping("/pipeline")
    public Mono<List<PipelineReportService.StageRow>> pipeline() {
        return pipeline.report();
    }

    @GetMapping("/activity")
    public Mono<List<ActivityReportService.TypeRow>> activity(
            @RequestParam Instant from,
            @RequestParam Instant to) {
        return activity.report(from, to);
    }
}
