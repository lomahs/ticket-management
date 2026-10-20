package tracker.adapters.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Failed logins per username and client address (the username stripped and lowercased, so "An" and "an " are one).
 * 5 failures within 15 minutes lock that pair for 15 minutes; a success clears the count. A login first reserves a slot
 * with {@link #tryAcquire}: attempts still in flight count toward the limit, so parallel requests cannot all pass before
 * the fifth failure is recorded. Each reservation ends with exactly one of {@link #succeeded}, {@link #failed} (a wrong
 * password) or {@link #released} (the directory could not be asked: not a failure). In memory, so a restart forgets it.
 */
@Component
public class LoginThrottle {
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(15);
    /** Above this many keys, idle entries are dropped, at most once per {@link #PRUNE_EVERY}. */
    static final int PRUNE_ABOVE = 10_000;
    static final Duration PRUNE_EVERY = Duration.ofMinutes(1);

    /** Changed only inside {@code entries.compute*}, which runs one function per key at a time. */
    private static final class Entry {
        final Deque<Instant> failures = new ArrayDeque<>();
        int inFlight;
        @Nullable Instant lockedUntil;

        void expire(Instant now) {
            if (lockedUntil != null && !now.isBefore(lockedUntil)) {
                lockedUntil = null;
            }
            while (!failures.isEmpty() && !failures.peekFirst().plus(WINDOW).isAfter(now)) {
                failures.pollFirst();
            }
        }

        /** Nothing to remember: not locked, nothing in flight, no recent failure. */
        boolean idle(Instant now) {
            expire(now);
            return lockedUntil == null && inFlight == 0 && failures.isEmpty();
        }
    }

    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastPrune = new AtomicReference<>(Instant.MIN);

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    static String key(String username, String client) {
        return username.strip().toLowerCase(Locale.ROOT) + "\u0000" + client;
    }

    /** Reserves an attempt; false when the pair is locked or its recent failures plus attempts in flight reach the limit. */
    public boolean tryAcquire(String username, String client) {
        Instant now = clock.instant();
        prune(now);
        boolean[] acquired = {false};
        entries.compute(key(username, client), (k, e) -> {
            Entry x = e != null ? e : new Entry();
            x.expire(now);
            if (x.lockedUntil == null && x.failures.size() + x.inFlight < MAX_FAILURES) {
                x.inFlight++;
                acquired[0] = true;
            }
            return x;
        });
        return acquired[0];
    }

    /** The reserved attempt had a wrong username or password: it becomes a failure, and the fifth one locks the pair. */
    public void failed(String username, String client) {
        Instant now = clock.instant();
        entries.compute(key(username, client), (k, e) -> {
            Entry x = e != null ? e : new Entry();
            x.inFlight = Math.max(0, x.inFlight - 1);
            x.expire(now);
            x.failures.addLast(now);
            if (x.failures.size() >= MAX_FAILURES) {
                x.lockedUntil = now.plus(LOCK);
                x.failures.clear();
            }
            return x;
        });
    }

    /** The reserved attempt could not be checked (the directory is down): the slot is freed, nothing is counted. */
    public void released(String username, String client) {
        Instant now = clock.instant();
        entries.computeIfPresent(key(username, client), (k, e) -> {
            e.inFlight = Math.max(0, e.inFlight - 1);
            return e.idle(now) ? null : e;
        });
    }

    /** The reserved attempt signed in: the count is cleared. */
    public void succeeded(String username, String client) {
        entries.computeIfPresent(key(username, client), (k, e) -> {
            e.inFlight = Math.max(0, e.inFlight - 1);
            e.failures.clear();
            e.lockedUntil = null;
            return e.inFlight == 0 ? null : e;
        });
    }

    int size() {
        return entries.size();
    }

    /** Drops idle entries when the map is large, at most once a minute; locked or in-flight entries are kept. */
    private void prune(Instant now) {
        if (entries.size() <= PRUNE_ABOVE) {
            return;
        }
        Instant last = lastPrune.get();
        if (now.isBefore(last.plus(PRUNE_EVERY)) || !lastPrune.compareAndSet(last, now)) {
            return;
        }
        for (String k : entries.keySet()) {
            entries.computeIfPresent(k, (key, e) -> e.idle(now) ? null : e);
        }
    }
}
