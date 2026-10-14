package com.itaccess.portal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Real Oracle, Kafka and Mailpit for integration tests (no H2, no embedded broker, no mocked SMTP:
 * Oracle SQL such as IDENTITY, OFFSET/FETCH, MERGE and TIMESTAMP WITH TIME ZONE must be proven
 * against Oracle itself). {@code @ServiceConnection} points the DataSource / Kafka bootstrap servers
 * at the containers; the mail settings are registered explicitly. One static container of each is
 * shared by every test context in the run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Oracle needs well over the 60s default to start when Docker is busy (e.g. pulling other images).
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
                    .withStartupTimeout(Duration.ofMinutes(4));

    // Same broker image and version as docker-compose.yml (KRaft mode, no ZooKeeper).
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"));

    // Same image as docker-compose.yml: SMTP on 1025, inbox API on 8025.
    @SuppressWarnings("resource")
    static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.3"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    /** Base URL of Mailpit's REST API, e.g. for reading the inbox in tests. */
    public static String mailpitApi() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1";
    }

    @Bean
    @ServiceConnection
    OracleContainer oracleFreeContainer() {
        return ORACLE;
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return KAFKA;
    }

    @Bean
    DynamicPropertyRegistrar mailpitProperties() {
        return registry -> {
            MAILPIT.start(); // no-op if already running
            registry.add("spring.mail.host", MAILPIT::getHost);
            registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        };
    }
}
