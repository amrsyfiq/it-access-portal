package com.itaccess.portal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Real Oracle and RabbitMQ for integration tests (no H2, no in-memory broker: Oracle SQL such as
 * IDENTITY, OFFSET/FETCH, MERGE and TIMESTAMP WITH TIME ZONE must be proven against Oracle itself).
 * {@code @ServiceConnection} points the DataSource / RabbitMQ connection at the containers.
 * One static container of each is shared by every test context in the run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Oracle needs well over the 60s default to start when Docker is busy (e.g. pulling other images).
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
                    .withStartupTimeout(Duration.ofMinutes(4));

    private static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));

    @Bean
    @ServiceConnection
    OracleContainer oracleFreeContainer() {
        return ORACLE;
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return RABBIT;
    }
}
