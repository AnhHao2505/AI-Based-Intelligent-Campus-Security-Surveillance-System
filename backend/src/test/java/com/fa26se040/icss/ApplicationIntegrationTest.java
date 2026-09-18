package com.fa26se040.icss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApplicationIntegrationTest {

    @Test
    @DisplayName("Context loads successfully and validates Flyway migrations against PostgreSQL")
    void contextLoads() {
        // Boots Spring context, runs Flyway migrations up to V26, and validates Hibernate schema
    }
}
