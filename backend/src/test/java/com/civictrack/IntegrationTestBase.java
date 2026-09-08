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
 * <p>There is no H2 fallback and there will not be one: H2 has no PostGIS, so
 * it cannot run {@code ST_DWithin}, {@code ST_Distance} on geography,
 * {@code ST_Contains}, a GiST index, or an advisory lock. Spatial logic gets
 * tested against real PostGIS or it does not get tested.
 *
 * <p><b>On matching production.</b> The deployed database is Supabase:
 * PostgreSQL 17.6 with PostGIS 3.3.7. This container is
 * {@code postgis/postgis:17-3.4}, so the <em>Postgres major matches</em> and
 * PostGIS is one minor version ahead. That gap is deliberate and not laziness:
 * no {@code postgis/postgis:17-3.3} image is published, and of the two axes
 * the Postgres major is the one that decides SQL syntax, locking semantics and
 * planner behaviour — which is what {@code ClusteringQueryPlanIT},
 * {@code ClusteringConcurrencyIT} and the {@code FOR UPDATE SKIP LOCKED} sweep
 * actually depend on.
 *
 * <p>The residual risk is using a PostGIS function that exists in 3.4 and not
 * in 3.3, which the suite would not catch. Every spatial function this project
 * calls — {@code ST_DWithin}, {@code ST_Distance}, {@code ST_Contains},
 * {@code ST_MakeEnvelope}, {@code ST_AsGeoJSON}, {@code ST_SnapToGrid},
 * {@code ST_SetSRID}, {@code ST_MakePoint}, {@code ST_X}, {@code ST_Y} —
 * predates PostGIS 3.0, so the exposure today is nil. Anything newer added
 * later must be checked against 3.3 by hand, or this comment is a lie.
 *
 * <p>An earlier version of this comment claimed the image was "the same tag as
 * production". It never was after the database was chosen, and a false parity
 * claim is worse than a stated gap.
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
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:17-3.4")
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
