package tracker.adapters.clock;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import tracker.application.ports.Clock;

/** Pins "today" (so demo data stays meaningful) while timestamps stay real. */
public final class FixedClock implements Clock {
    private final String today;

    public FixedClock(String today) {
        this.today = today;
    }

    @Override
    public String today() {
        return today;
    }

    @Override
    public Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
