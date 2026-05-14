package com.kumouri.kmodigipresbe;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        // Use the JVM-based Apache Kafka image, not the native-image variant.
        // apache/kafka-native:latest segfaults intermittently during early VM init
        // (com.oracle.svm.core.posix.headers.Pwd.getpwuid) on ubuntu-latest GitHub
        // runners — same crash, two runs in a row. The JVM image starts a few seconds
        // slower but doesn't crash.
        return new KafkaContainer(DockerImageName.parse("apache/kafka:latest"));
    }

    @Bean
    @ServiceConnection
    MongoDBContainer mongoDbContainer() {
        return new MongoDBContainer(DockerImageName.parse("mongo:latest"));
    }

}
