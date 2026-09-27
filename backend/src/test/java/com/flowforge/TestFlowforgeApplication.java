package com.flowforge;

import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against a Testcontainers PostgreSQL instead of the Compose database.
 */
public class TestFlowforgeApplication {

    public static void main(String[] args) {
        SpringApplication.from(FlowforgeApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
