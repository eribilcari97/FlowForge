package com.flowforge;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a real PostgreSQL container for integration tests. {@code @ServiceConnection} points the
 * datasource at it, so Flyway and JPA run exactly as they do against the Compose database.
 * Test contexts with the same configuration are cached, so the container is shared between test classes.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    }

    @Bean
    MailpitContainer mailpitContainer() {
        return new MailpitContainer();
    }

    @Bean
    DynamicPropertyRegistrar mailServerProperties(MailpitContainer mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", mailpit::smtpPort);
        };
    }
}
