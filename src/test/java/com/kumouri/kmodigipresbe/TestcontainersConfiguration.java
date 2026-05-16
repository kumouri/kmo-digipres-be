package com.kumouri.kmodigipresbe;

import com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // ---------------------------------------------------------------------------
    // Singleton containers — started ONCE per test JVM, reused by EVERY
    // @SpringBootTest ApplicationContext. The @Bean methods below return these same
    // running instances, so Spring Boot @ServiceConnection wires every context
    // (~22 distinct @TestPropertySource variants => ~22 cached contexts) to one
    // shared Mongo + one shared Kafka instead of a per-context pair. Per-context
    // containers with no Ryuk reaper exhausted the CI runner once the full IT suite
    // ran. Requires a single forked test JVM (Gradle: forkEvery=0/maxParallelForks=1)
    // for "static == one per suite" to hold; Testcontainers' JVM shutdown hook + Ryuk
    // (re-enabled in CI) stop them at the end.
    // ---------------------------------------------------------------------------
    private static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:latest"));

    // JVM-based Apache Kafka image, not the native-image variant:
    // apache/kafka-native:latest segfaults intermittently during early VM init
    // (com.oracle.svm.core.posix.headers.Pwd.getpwuid) on ubuntu-latest GitHub
    // runners. The JVM image starts a few seconds slower but doesn't crash.
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:latest"));

    static {
        MONGO.start();
        KAFKA.start();
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return KAFKA;
    }

    @Bean
    @ServiceConnection
    MongoDBContainer mongoDbContainer() {
        return MONGO;
    }

    /**
     * Shared mock {@link EmbeddingService} that prevents OpenAI HTTP calls in CI.
     * Declared here (rather than via {@code @MockBean} in individual tests) so that
     * all {@code @SpringBootTest} contexts share the same ApplicationContext and only
     * one Testcontainers MongoDB + Kafka pair is started per context configuration.
     *
     * <p>Tests that need to configure specific behavior (e.g. {@code EmbeddingPipelineIT})
     * should call {@code Mockito.reset(embeddingService)} followed by {@code when(...)}
     * in their {@code @BeforeEach} setup, then restore default behaviour via the same
     * pattern in {@code @AfterEach} if necessary.
     */
    @Bean
    @Primary
    EmbeddingService embeddingService() {
        EmbeddingService mock = Mockito.mock(EmbeddingService.class);
        Mockito.when(mock.embed(any(UUID.class), anyString()))
                .thenReturn(Mono.just(new float[1536]));
        Mockito.when(mock.providerName()).thenReturn("mock");
        return mock;
    }

}
