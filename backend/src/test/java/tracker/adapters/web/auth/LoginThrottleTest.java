package tracker.adapters.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginThrottleTest {
    static final String IP = "127.0.0.1";

    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-07T09:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final TestClock clock = new TestClock();
    final LoginThrottle throttle = new LoginThrottle(clock);

    void fail(String user, String ip) {
        assertThat(throttle.tryAcquire(user, ip)).isTrue();
        throttle.failed(user, ip);
    }

    @Test
    void fiveFailuresLockTheKeyWhateverTheCase() {
        for (int i = 0; i < 4; i++) {
            fail(i % 2 == 0 ? "an" : " AN ", IP);
        }
        assertThat(throttle.tryAcquire("an", IP)).as("the fifth attempt may still run").isTrue();
        throttle.failed("an", IP);
        assertThat(throttle.tryAcquire("An", IP)).isFalse();
        assertThat(throttle.tryAcquire("an", "10.0.0.9")).as("another client is not locked").isTrue();
        assertThat(throttle.tryAcquire("binh", IP)).as("another username is not locked").isTrue();
    }

    @Test
    void theLockExpiresAfter15Minutes() {
        for (int i = 0; i < 5; i++) {
            fail("an", IP);
        }
        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(throttle.tryAcquire("an", IP)).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(throttle.tryAcquire("an", IP)).isTrue();
    }

    @Test
    void failuresOlderThanTheWindowDoNotCount() {
        for (int i = 0; i < 4; i++) {
            fail("an", IP);
        }
        clock.advance(Duration.ofMinutes(15));
        fail("an", IP);
        assertThat(throttle.tryAcquire("an", IP)).isTrue();
    }

    @Test
    void aSuccessClearsTheCount() {
        for (int i = 0; i < 4; i++) {
            fail("an", IP);
        }
        assertThat(throttle.tryAcquire("an", IP)).isTrue();
        throttle.succeeded("an", IP);
        for (int i = 0; i < 4; i++) {
            fail("an", IP);
        }
        assertThat(throttle.tryAcquire("an", IP)).as("only 4 failures since the success").isTrue();
    }

    @Test
    void attemptsInFlightCountTowardTheLimit() {
        for (int i = 0; i < 5; i++) {
            assertThat(throttle.tryAcquire("an", IP)).isTrue();
        }
        assertThat(throttle.tryAcquire("an", IP)).as("five attempts are still running").isFalse();
        throttle.released("an", IP);
        assertThat(throttle.tryAcquire("an", IP)).as("a released slot is free again").isTrue();
    }

    @Test
    void anUnavailableDirectoryIsNotAFailure() {
        for (int i = 0; i < 20; i++) {
            assertThat(throttle.tryAcquire("an", IP)).isTrue();
            throttle.released("an", IP);
        }
        for (int i = 0; i < 4; i++) {
            fail("an", IP);
        }
        assertThat(throttle.tryAcquire("an", IP)).isTrue();
        assertThat(throttle.size()).isEqualTo(1);
    }

    @Test
    void idleEntriesArePrunedButLockedOnesAreKept() {
        for (int i = 0; i < 5; i++) {
            fail("an", IP);
        }
        for (int i = 0; i < LoginThrottle.PRUNE_ABOVE; i++) {
            fail("user" + i, IP);
        }
        clock.advance(Duration.ofMinutes(16)); // every failure is outside the window and an's lock has expired
        for (int i = 0; i < 5; i++) {
            fail("binh", IP);
        }
        assertThat(throttle.tryAcquire("chi", IP)).isTrue(); // binh's first attempt above already pruned the idle entries
        assertThat(throttle.size()).as("binh (locked) and chi (in flight) are kept").isEqualTo(2);
        assertThat(throttle.tryAcquire("binh", IP)).isFalse();
    }
}
