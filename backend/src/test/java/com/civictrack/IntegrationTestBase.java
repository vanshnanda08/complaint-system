package com.civictrack;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for every integration test in the project.
 *
 * <p>The container is a real {@code postgis/postgis:16-3.4} — the same image
 * tag as production. There is no H2 fallback and there will not be one: H2 has
 * no PostGIS, so it cannot run {@code ST_DWithin}, {@code ST_Distance} on
 * geography, {@code ST_Contains}, a GiST index, or an advisory lock. Spatial
 * logic gets tested against real PostGIS or it does not get tested.
 *
 * <p>The container is {@code static} and never stopped, so JUnit reuses one
 * instance across every test class in the run. Ryuk cleans it up when the JVM
 * exits. Starting a container per class would add roughly a second per class
 * for no isolation benefit, since Flyway rebuilds the schema deterministically.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("civictrack_test")
                    .withUsername("civictrack")
                    .withPassword("civictrack");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
