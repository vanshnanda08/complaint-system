package com.civictrack.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock tests can move.
 *
 * <p>This is why {@link Clock} is a bean rather than a static call. Three of
 * the behaviours this phase adds are functions of elapsed time -- a deadline
 * breaching, a paused clock not advancing, a neglected issue's priority rising
 * -- and the only alternative way to test them is to sleep. Sleeping makes the
 * suite slow, makes it flaky, and caps what can be asserted at whatever a test
 * is willing to wait for, which is nowhere near the three days the ageing term
 * is interesting over.
 *
 * <p>Starts at the real current instant, so a test that does not touch it
 * behaves exactly as it did before this class existed.
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock() {
        this(Instant.now(), ZoneId.of("UTC"));
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void set(Instant now) {
        this.instant = now;
    }

    public void advance(Duration by) {
        this.instant = this.instant.plus(by);
    }

    public void advanceHours(long hours) {
        advance(Duration.ofHours(hours));
    }

    /** Back to real time, for tests that must not leak an advanced clock. */
    public void reset() {
        this.instant = Instant.now();
    }
}
