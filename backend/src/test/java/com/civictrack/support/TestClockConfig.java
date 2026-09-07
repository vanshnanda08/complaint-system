package com.civictrack.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Substitutes the mutable clock into every integration test.
 *
 * <p>It sits under {@code com.civictrack} in the test sources, so component
 * scanning finds it in tests and never in the packaged application. Marked
 * {@code @Primary} rather than named {@code clock}, because a same-named bean
 * would be a definition override, which Boot rejects by default and rightly so.
 */
@Configuration
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
