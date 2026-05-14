package com.kumouri.kmodigipresbe.controller.report;

import com.kumouri.kmodigipresbe.model.report.Dashboard;
import com.kumouri.kmodigipresbe.model.report.SavedReport;
import com.kumouri.kmodigipresbe.service.report.ReportRunner;
import com.kumouri.kmodigipresbe.service.report.SavedReportCrudService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class SavedReportController {

    private final SavedReportCrudService service;
    private final ReportRunner runner;

    // SavedReport CRUD
    @GetMapping("/saved")
    public Flux<SavedReport> listReports() {
        return service.listReports();
    }

    @GetMapping("/saved/{id}")
    public Mono<SavedReport> getReport(@PathVariable UUID id) {
        return service.findReport(id);
    }

    @PostMapping("/saved")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SavedReport> createReport(@RequestBody SavedReport body) {
        return service.createReport(body);
    }

    @PutMapping("/saved/{id}")
    public Mono<SavedReport> updateReport(@PathVariable UUID id, @RequestBody SavedReport body) {
        return service.updateReport(id, body);
    }

    @DeleteMapping("/saved/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteReport(@PathVariable UUID id) {
        return service.deleteReport(id);
    }

    @PostMapping("/saved/{id}/run")
    public Mono<List<Map<String, Object>>> runReport(@PathVariable UUID id) {
        return service.findReport(id).flatMap(runner::run);
    }

    // Dashboard CRUD
    @GetMapping("/dashboards")
    public Flux<Dashboard> listDashboards() {
        return service.listDashboards();
    }

    @GetMapping("/dashboards/{id}")
    public Mono<Dashboard> getDashboard(@PathVariable UUID id) {
        return service.findDashboard(id);
    }

    @PostMapping("/dashboards")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Dashboard> createDashboard(@RequestBody Dashboard body) {
        return service.createDashboard(body);
    }

    @PutMapping("/dashboards/{id}")
    public Mono<Dashboard> updateDashboard(@PathVariable UUID id, @RequestBody Dashboard body) {
        return service.updateDashboard(id, body);
    }

    @DeleteMapping("/dashboards/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteDashboard(@PathVariable UUID id) {
        return service.deleteDashboard(id);
    }
}
