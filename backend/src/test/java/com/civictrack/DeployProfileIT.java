package com.civictrack;

import com.civictrack.sla.SlaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deploy profile must not compress the clock.
 *
 * <p>This guards a trap rather than a feature. Staff logins only exist because
 * {@code DemoAccountBootstrap} sets passwords on the seeded accounts, and that
 * bootstrap was originally gated on the {@code demo} profile -- which also sets
 * three-minute SLAs. Deploying with {@code demo} to get the logins would have
 * made every issue in the corpus read as overdue, and the public dashboard is
 * the screen this project exists to argue for.
 *
 * <p>If somebody later "simplifies" this by folding deploy back into demo, or
 * copies demo's timings into application-deploy.yml, this test fails and says
 * why. The failure mode it prevents is not a crash: it is a deployment that
 * works perfectly and reports false numbers.
 */
@ActiveProfiles({"test", "deploy"})
class DeployProfileIT extends IntegrationTestBase {

    @Autowired private SlaProperties sla;

    @Test
    @DisplayName("the deploy profile leaves real SLA deadlines in place")
    void deployDoesNotCompressTheClock() {
        assertThat(sla.demoOverrideActive())
                .as("a deployed environment must judge departments on their real deadlines")
                .isFalse();
        assertThat(sla.demoOverrideHours()).isZero();
    }

    @Test
    @DisplayName("the deploy profile keeps the production sweep cadence and re-arm floor")
    void deployKeepsProductionTimings() {
        assertThat(sla.cron())
                .as("the twenty-second demo sweep is for a projector, not a deployment")
                .isEqualTo("0 */5 * * * *");
        assertThat(sla.rearmFloor())
                .as("the one-minute demo floor would hand crews deadlines nobody can meet")
                .isEqualTo(Duration.ofHours(2));
    }
}
