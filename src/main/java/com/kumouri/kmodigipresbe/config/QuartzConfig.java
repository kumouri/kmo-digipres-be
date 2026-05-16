package com.kumouri.kmodigipresbe.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import java.util.Properties;

/**
 * Quartz scheduler configuration for the KMOSF CRM.
 *
 * <h2>Phase E state (E-D5 — the resolved headline risk)</h2>
 * Phase A shipped Quartz with a RAM store because the {@code quartz-mongodb}
 * coordinate did not resolve from Maven Central. Phase E resolves it to
 * {@code io.fluidsonic.mirror:quartz-mongodb:2.2.0-rc2} (a Maven-Central mirror of
 * the JCenter-sunset {@code com.novemberain} library, republished WITHOUT
 * repackaging — the {@code JobStore} FQN stays
 * {@code com.novemberain.quartz.mongodb.MongoDBJobStore}) and swaps to a durable
 * Mongo store when {@code kmosf.quartz.store=mongo}.
 *
 * <h2>Money-durability is NOT the JobStore's responsibility</h2>
 * Even with the RAM store (the E-D5 justified fallback if the Mongo store fails to
 * boot under the Spring-Boot-managed Quartz/Mongo-driver versions), recurring-billing
 * money-correctness is fully preserved: the durable record of "which periods were
 * already spawned" lives in the {@code RecurringInvoiceOccurrence} unique-indexed
 * Mongo ledger + the {@code RecurringInvoice.nextRunAt} cursor (E-D2/E-D3). A
 * stateless RAM-store trigger that re-runs {@code runDueOnce()} hourly reconciles
 * from that ledger on every tick (bounded catch-up) — exactly the
 * {@code ServiceAgreementSchedulerService} model.
 *
 * <h2>{@code @Scheduled} co-existence</h2>
 * The 8 existing {@code @Scheduled} services ({@code ReportScheduler},
 * {@code SlaBreachScheduler}, etc.) are intentionally untouched (R3 scope
 * discipline). The Mongo Quartz store and Spring {@code @Scheduled} coexist
 * unchanged.
 */
@Slf4j
@Configuration
public class QuartzConfig {

    /**
     * {@code mongo} (durable Mongo JobStore) or {@code memory} (RAM store —
     * Phase A default / E-D5 fallback). Defaults to {@code memory} so a boot
     * failure under the Mongo store can be diagnosed without changing code.
     */
    @Value("${kmosf.quartz.store:memory}")
    private String quartzStore;

    private final MongoProperties mongoProperties;

    public QuartzConfig(MongoProperties mongoProperties) {
        this.mongoProperties = mongoProperties;
    }

    /**
     * Customizer for the Spring Quartz {@link SchedulerFactoryBean}.
     *
     * <p>When {@code kmosf.quartz.store=mongo}, injects the Mongo JobStore per the
     * verbatim Phase-E snippet (Mongo store class, URI, dbName,
     * {@code collectionPrefix=quartz_}, {@code instanceId=AUTO}). Otherwise a no-op
     * beyond what Spring Boot auto-configures from {@code spring.quartz.*}.
     */
    @Bean
    public SchedulerFactoryBeanCustomizer quartzCustomizer() {
        return schedulerFactoryBean -> {
            if (!"mongo".equalsIgnoreCase(quartzStore)) {
                log.info("Quartz store = {} (RAM store; durability lives in the "
                        + "RecurringInvoiceOccurrence ledger + nextRunAt cursor — E-D5).",
                        quartzStore);
                return;
            }
            Properties props = new Properties();
            props.setProperty("org.quartz.jobStore.class",
                    "com.novemberain.quartz.mongodb.MongoDBJobStore");
            props.setProperty("org.quartz.jobStore.mongoUri", mongoProperties.determineUri());
            props.setProperty("org.quartz.jobStore.dbName", mongoProperties.getMongoClientDatabase());
            props.setProperty("org.quartz.jobStore.collectionPrefix", "quartz_");
            props.setProperty("org.quartz.scheduler.instanceId", "AUTO");
            props.setProperty("org.quartz.threadPool.threadCount", "5");
            schedulerFactoryBean.setQuartzProperties(props);
            log.info("Quartz store = mongo (com.novemberain.quartz.mongodb.MongoDBJobStore, "
                    + "collectionPrefix=quartz_, db={}).", mongoProperties.getMongoClientDatabase());
        };
    }
}
