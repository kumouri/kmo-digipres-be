package com.kumouri.kmodigipresbe.service.report;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.RuleCondition;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.report.AggregationOp;
import com.kumouri.kmodigipresbe.model.report.AggregationSpec;
import com.kumouri.kmodigipresbe.model.report.SavedReport;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies ReportRunner aggregations against seeded deals.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReportRunnerIT {

    @Autowired DealRepository deals;
    @Autowired ReportRunner runner;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Deal.class).block();
        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        seedDeal("Acme", PipelineStage.NEW, 1000);
        seedDeal("Beta", PipelineStage.NEW, 2000);
        seedDeal("Gamma", PipelineStage.WON, 5000);
        seedDeal("Delta", PipelineStage.WON, 7000);
        seedDeal("Epsilon", PipelineStage.LOST, 3000);
    }

    private void seedDeal(String title, PipelineStage stage, long value) {
        deals.save(Deal.builder()
                        .title(title).stage(stage)
                        .value(BigDecimal.valueOf(value))
                        .currency("USD")
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
    }

    @Test
    void seededDealsHaveValuesInMongo_diagnostic() {
        List<org.bson.Document> docs = mongo.findAll(org.bson.Document.class, "deals")
                .collectList().block();
        assertThat(docs).isNotNull();
        for (org.bson.Document d : docs) {
            System.out.println("RAW deal: " + d.toJson());
        }
        assertThat(docs).hasSize(5);
    }

    @Test
    void countAndSumByStage_returnsExpectedGroups() {
        SavedReport report = SavedReport.builder()
                .name("Deals by stage")
                .entityType("deals")
                .groupBy(List.of("stage"))
                .aggregations(List.of(
                        AggregationSpec.builder()
                                .field("_id").op(AggregationOp.COUNT).outputName("count").build(),
                        AggregationSpec.builder()
                                .field("value").op(AggregationOp.SUM).outputName("totalValue").build()))
                .build();

        List<Map<String, Object>> rows = runner.runForTenant(report, tenantId).block();

        assertThat(rows).isNotNull().hasSize(3);
        Map<String, Long> counts = new java.util.HashMap<>();
        Map<String, Number> totals = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put((String) row.get("stage"), ((Number) row.get("count")).longValue());
            totals.put((String) row.get("stage"), (Number) row.get("totalValue"));
        }
        assertThat(counts).containsEntry("NEW", 2L).containsEntry("WON", 2L).containsEntry("LOST", 1L);
        assertThat(totals.get("WON").longValue()).isEqualTo(12000);
        assertThat(totals.get("NEW").longValue()).isEqualTo(3000);
    }

    @Test
    void filterByStage_narrowsResult() {
        SavedReport report = SavedReport.builder()
                .name("Won deals only")
                .entityType("deals")
                .filterTree(List.of(RuleCondition.builder()
                        .field("stage").op(RuleCondition.Op.EQUALS).value("WON").build()))
                .groupBy(List.of("stage"))
                .aggregations(List.of(AggregationSpec.builder()
                        .field("_id").op(AggregationOp.COUNT).outputName("count").build()))
                .build();

        List<Map<String, Object>> rows = runner.runForTenant(report, tenantId).block();

        assertThat(rows).isNotNull().hasSize(1);
        assertThat(rows.get(0).get("stage")).isEqualTo("WON");
        assertThat(((Number) rows.get(0).get("count")).longValue()).isEqualTo(2);
    }

    @Test
    void tenantIsolation_otherTenantSeesNothing() {
        UUID otherTenant = UUID.randomUUID();
        SavedReport report = SavedReport.builder()
                .entityType("deals")
                .aggregations(List.of(AggregationSpec.builder()
                        .field("_id").op(AggregationOp.COUNT).outputName("count").build()))
                .build();

        List<Map<String, Object>> rows = runner.runForTenant(report, otherTenant).block();

        assertThat(rows).isNotNull();
        if (!rows.isEmpty()) {
            // No groupBy → a single bucket with count=0 acceptable, or 0 rows.
            assertThat(((Number) rows.get(0).get("count")).longValue()).isZero();
        }
    }
}
