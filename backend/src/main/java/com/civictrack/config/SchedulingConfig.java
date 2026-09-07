package com.civictrack.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Distributed scheduler locking.
 *
 * <p>The SLA sweep escalates issues and notifies people, so running it twice
 * concurrently on two instances is not a performance problem, it is a
 * correctness one. ShedLock is the first of the three idempotency layers; the
 * other two are in the database and hold even when this one does not, which is
 * what makes a redeploy overlap safe rather than merely unlikely.
 *
 * <p>{@code usingDbTime} makes the lock's clock the database's clock. With
 * instance clocks, a machine whose time drifted forward could release a lock
 * early and overlap with the instance still holding it -- one shared clock
 * removes an entire class of problem that is otherwise invisible until it
 * happens in production.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class SchedulingConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
