package com.kumouri.kmodigipresbe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 9d enables {@link EnableScheduling} so {@code SequenceEngine}'s tick
 * fires every minute. Quartz auto-config (on the classpath via
 * {@code spring-boot-starter-quartz}) coexists fine — Spring's {@code @Scheduled}
 * is a separate scheduler used for the lightweight polling case.
 */
@SpringBootApplication
@EnableScheduling
public class KmoDigipresBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KmoDigipresBeApplication.class, args);
    }

}
