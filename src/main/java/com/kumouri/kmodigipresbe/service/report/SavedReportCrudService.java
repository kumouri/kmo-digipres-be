package com.kumouri.kmodigipresbe.service.report;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.report.Dashboard;
import com.kumouri.kmodigipresbe.model.report.SavedReport;
import com.kumouri.kmodigipresbe.repository.DashboardRepository;
import com.kumouri.kmodigipresbe.repository.SavedReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SavedReportCrudService {

    private final SavedReportRepository savedReports;
    private final DashboardRepository dashboards;

    public Flux<SavedReport> listReports() {
        return savedReports.findAll();
    }

    public Mono<SavedReport> findReport(UUID id) {
        return savedReports.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "SavedReport not found", 2201, 404)));
    }

    public Mono<SavedReport> createReport(SavedReport toCreate) {
        toCreate.setId(null);
        return savedReports.save(toCreate);
    }

    public Mono<SavedReport> updateReport(UUID id, SavedReport patch) {
        return findReport(id).flatMap(existing -> {
            if (patch.getName() != null) existing.setName(patch.getName());
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getEntityType() != null) existing.setEntityType(patch.getEntityType());
            if (patch.getFilterTree() != null) existing.setFilterTree(patch.getFilterTree());
            if (patch.getGroupBy() != null) existing.setGroupBy(patch.getGroupBy());
            if (patch.getAggregations() != null) existing.setAggregations(patch.getAggregations());
            if (patch.getChartHint() != null) existing.setChartHint(patch.getChartHint());
            existing.setScheduleCron(patch.getScheduleCron()); // null clears the schedule
            if (patch.getScheduleRecipients() != null) {
                existing.setScheduleRecipients(patch.getScheduleRecipients());
            }
            return savedReports.save(existing);
        });
    }

    public Mono<Void> deleteReport(UUID id) {
        return savedReports.deleteById(id);
    }

    public Flux<Dashboard> listDashboards() {
        return dashboards.findAll();
    }

    public Mono<Dashboard> findDashboard(UUID id) {
        return dashboards.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Dashboard not found", 2202, 404)));
    }

    public Mono<Dashboard> createDashboard(Dashboard toCreate) {
        toCreate.setId(null);
        return dashboards.save(toCreate);
    }

    public Mono<Dashboard> updateDashboard(UUID id, Dashboard patch) {
        return findDashboard(id).flatMap(existing -> {
            if (patch.getName() != null) existing.setName(patch.getName());
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getItems() != null) existing.setItems(patch.getItems());
            return dashboards.save(existing);
        });
    }

    public Mono<Void> deleteDashboard(UUID id) {
        return dashboards.deleteById(id);
    }
}
