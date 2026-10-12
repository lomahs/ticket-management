package tracker.application.ports;

import java.time.Instant;

public interface Clock {
    /** YYYY-MM-DD in the team's time zone. */
    String today();

    Instant now();
}
