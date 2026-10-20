package tracker.adapters.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Failed logins per username (stripped and lowercased, so "An" and "an " are one): 5 failures within 15 minutes lock the
 * username for 15 minutes; a success clears the count. In memory, so a restart forgets it.
 */
@Component
public class LoginThrottle {
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(15);
    /** Above this many usernames, entries with nothing recent are dropped. */
    private static final int PRUNE_ABOVE = 10_000;

    private static final class Entry {
        final Deque<Instant> failures = new ArrayDeque<>();
        @Nullable Instant lockedUntil;

        boolean stale(Instant now) {
            return (lockedUntil == null || !now.isBefore(lockedUntil))
                    && (failures.isEmpty() || !failures.peekLast().plus(WINDOW).isAfter(now));
        }
    }

    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    static String key(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    public boolean locked(String username) {
        Instant now = clock.instant();
        Entry e = entries.get(key(username));
        if (e == null) {
            return false;
        }
        synchronized (e) {
            return e.lockedUntil != null && now.isBefore(e.lockedUntil);
        }
    }

    public void failed(String username) {
        Instant now = clock.instant();
        if (entries.size() > PRUNE_ABOVE) {
            entries.entrySet().removeIf(x -> {
                synchronized (x.getValue()) {
                    return x.getValue().stale(now);
                }
            });
        }
        Entry e = entries.computeIfAbsent(key(username), k -> new Entry());
        synchronized (e) {
            while (!e.failures.isEmpty() && !e.failures.peekFirst().plus(WINDOW).isAfter(now)) {
                e.failures.pollFirst();
            }
            e.failures.addLast(now);
            if (e.failures.size() >= MAX_FAILURES) {
                e.lockedUntil = now.plus(LOCK);
                e.failures.clear();
            }
        }
    }

    public void succeeded(String username) {
        entries.remove(key(username));
    }
}
