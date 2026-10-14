package com.itaccess.portal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Real Oracle and Kafka for integration tests (no H2, no embedded broker: Oracle SQL such as
 * IDENTITY, OFFSET/FETCH, MERGE and TIMESTAMP WITH TIME ZONE must be proven against Oracle itself).
 * {@code @ServiceConnection} points the DataSource / Kafka bootstrap servers at the containers.
 * One static container of each is shared by every test context in the run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Oracle needs well over the 60s default to start when Docker is busy (e.g. pulling other images).
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
                    .withStartupTimeout(Duration.ofMinutes(4));

    // Same broker image and version as docker-compose.yml (KRaft mode, no ZooKeeper).
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"));

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
}
