package tracker.adapters.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import tracker.application.ports.Clock;

/** A real clock in the team's time zone. Timestamps are kept to the microsecond, as the database stores them. */
public final class SystemClock implements Clock {
    private final ZoneId tz;

    public SystemClock() {
        this("Asia/Ho_Chi_Minh");
    }

    public SystemClock(String tz) {
        this.tz = ZoneId.of(tz);
    }

    @Override
    public String today() {
        return LocalDate.now(tz).toString();
    }

    @Override
    public Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
