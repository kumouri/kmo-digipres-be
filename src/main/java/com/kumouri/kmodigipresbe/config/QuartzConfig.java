package com.kumouri.kmodigipresbe.config;

import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * Quartz scheduler configuration for the KMOSF CRM.
 *
 * <h2>Phase A state</h2>
 * Phase A ships a no-op proof job ({@link com.kumouri.kmodigipresbe.scheduling.NoOpQuartzJob})
 * that verifies the Quartz scheduler boots correctly.  The store type is currently
 * {@code memory} (Spring Boot default) because the Quartz Mongo JobStore library
 * ({@code com.github.quartz-mongodb}) could not be resolved at implementation time.
 *
 * <h2>Phase E plan</h2>
 * When the Mongo store library is available:
 * <ol>
 *   <li>Add the library dependency to {@code build.gradle}.</li>
 *   <li>Switch {@code spring.quartz.job-store-type} to {@code mongo} (or configure
 *       the raw {@code org.quartz.jobStore.class} property).</li>
 *   <li>Update this customizer to inject {@code MongoProperties} (URI + database)
 *       into the Quartz {@code SchedulerFactoryBean}.</li>
 *   <li>Collections created by the store: {@code quartz_jobs}, {@code quartz_triggers},
 *       {@code quartz_locks}, {@code quartz_calendars}.</li>
 * </ol>
 *
 * <h2>{@code @Scheduled} co-existence</h2>
 * The 8 existing {@code @Scheduled} services ({@code ReportScheduler},
 * {@code SlaBreachScheduler}, etc.) are intentionally untouched in Phase A.
 * Migration to Quartz jobs is planned but out-of-scope until Phase E.
 */
@Configuration
public class QuartzConfig {

    /**
     * Customizer for the Spring Quartz {@link SchedulerFactoryBean}.
     *
     * <p>Currently a no-op beyond what Spring Boot auto-configures from
     * {@code spring.quartz.*} properties.  Placeholder for Phase E Mongo store
     * injection.
     *
     * <p>When the Mongo JobStore library is available, replace this with:
     * <pre>{@code
     * SchedulerFactoryBeanCustomizer mongoStoreCustomizer(MongoProperties mongoProperties) {
     *     return factory -> {
     *         Properties props = new Properties();
     *         props.setProperty("org.quartz.jobStore.class",
     *             "com.novemberain.quartz.mongodb.MongoDBJobStore");
     *         props.setProperty("org.quartz.jobStore.mongoUri", mongoProperties.determineUri());
     *         props.setProperty("org.quartz.jobStore.dbName", mongoProperties.getDatabase());
     *         props.setProperty("org.quartz.jobStore.collectionPrefix", "quartz_");
     *         props.setProperty("org.quartz.scheduler.instanceId", "AUTO");
     *         factory.setQuartzProperties(props);
     *     };
     * }
     * }</pre>
     */
    @Bean
    public SchedulerFactoryBeanCustomizer quartzCustomizer() {
        return schedulerFactoryBean -> {
            // Phase A: no additional customization beyond application.properties defaults.
            // Phase E: inject Mongo store URI + dbName here.
        };
    }
}
