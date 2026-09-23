package com.fa26se040.icss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApplicationIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("Context loads successfully and validates Flyway migrations against test PostgreSQL")
    void contextLoads() {
        // Boots Spring context, runs Flyway migrations against campus_security_test, and validates Hibernate schema
    }
}
