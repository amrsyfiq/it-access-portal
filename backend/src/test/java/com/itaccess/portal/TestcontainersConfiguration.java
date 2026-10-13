package com.itaccess.portal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Oracle for integration tests (no H2: Oracle SQL such as IDENTITY, OFFSET/FETCH and
 * TIMESTAMP WITH TIME ZONE must be proven against Oracle itself).
 * {@code @ServiceConnection} points the DataSource at the container automatically.
 * One static container is shared by every test context in the run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"));

    @Bean
    @ServiceConnection
    OracleContainer oracleFreeContainer() {
        return ORACLE;
    }
}
