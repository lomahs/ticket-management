package tracker.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import tracker.adapters.clock.FixedClock;
import tracker.adapters.clock.SystemClock;
import tracker.adapters.fake.Dataset;
import tracker.adapters.fake.FakeJira;
import tracker.adapters.jira.JiraCloudReader;
import tracker.adapters.jira.JiraConfig;
import tracker.adapters.persistence.Database;
import tracker.adapters.persistence.FernetSecretBox;
import tracker.application.Deps;
import tracker.application.ports.Clock;
import tracker.application.ports.UnitOfWork;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Py;

/**
 * Composition root: reads the environment, picks adapters, builds the dependencies.
 *
 * <pre>
 *   TRACKER_MODE        demo | jira (default jira)
 *   TRACKER_DB_URL      sqlite:///path or a JDBC URL (default: sqlite at backend-java/data/tracker.db;
 *                       demo: .tmp/demo-java/tracker.db, recreated)
 *   TRACKER_TODAY       pin "today" (demo default 2026-10-07)
 *   TRACKER_SECRET_KEY  Fernet key for the stored Jira token (default: backend-java/data/secret.key, created on first run)
 *   TRACKER_STATIC_DIR  built frontend to serve (default: frontend/dist when it exists)
 * </pre>
 * Paths are relative to the backend-java directory (the working directory when run with the scripts).
 */
public final class Bootstrap {
    private Bootstrap() {
    }

    /** The backend-java directory: the working directory, or the system property tracker.home. */
    public static Path backend() {
        return Path.of(System.getProperty("tracker.home", "")).toAbsolutePath().normalize();
    }

    /** The repository root (frontend/, .tmp/). */
    public static Path root() {
        Path b = backend();
        return b.getParent() != null ? b.getParent() : b;
    }

    public static Path data() {
        return backend().resolve("data");
    }

    /** The wired dependencies and what must be closed with them. */
    public record App(Deps deps, Database db) {
    }

    private static void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot remove " + p, e);
        }
    }

    /** Fresh database seeded with the demo team and synced from the simulated Jira site. */
    public static App buildDemo(Path dbPath, String today) {
        for (String suffix : List.of("", "-wal", "-shm")) {
            delete(Path.of(dbPath + suffix));
        }
        Database db = new Database("sqlite:///" + dbPath.toAbsolutePath());
        db.migrate();
        FernetSecretBox box = FernetSecretBox.fromKeyFile(dbPath.resolveSibling("secret.key"));
        try (UnitOfWork u = db.begin()) {
            FakeJira.seedTracker(u, box);
            u.commit();
        }
        Clock clock = new FixedClock(today);
        FakeJira jira = new FakeJira(clock);
        Deps deps = new Deps(db, clock, jira, jira, box, (url, email, token) -> jira, "demo");
        deps.sync().run(true);
        return new App(deps, db);
    }

    public static App buildJira(String dbUrl, String today) {
        Database db = new Database(dbUrl);
        db.migrate();
        FernetSecretBox box = FernetSecretBox.fromKeyFile(data().resolve("secret.key"));
        Supplier<JiraConfig> config = () -> {
            TrackerSettings s;
            try (UnitOfWork u = db.begin()) {
                s = u.settings().load();
            }
            TrackerSettings.FieldMapping fm = s.fieldMapping;
            List<String> fields = java.util.stream.Stream.of(fm.plannedStart, fm.plannedEnd, fm.effort).filter(Py::truthy).toList();
            return new JiraConfig(s.jira.url, s.jira.email, box.decrypt(s.jira.tokenEncrypted), fields);
        };
        Clock clock = Py.truthy(today) ? new FixedClock(today) : new SystemClock();
        // Phase 1 reads Jira only: no writer, so the UI disables Jira edits.
        Deps deps = new Deps(db, clock, new JiraCloudReader(config), null, box,
                (url, email, token) -> new JiraCloudReader(() -> new JiraConfig(url, email, token, List.of())), "jira");
        return new App(deps, db);
    }

    /** An environment variable, or the system property of the same name (handy for tests and -D flags). */
    static String env(String name, String dflt) {
        String v = System.getenv(name);
        if (v == null) {
            v = System.getProperty(name);
        }
        return v != null ? v : dflt;
    }

    public static String mode() {
        return env("TRACKER_MODE", "jira");
    }

    public static Path staticDir() {
        return Path.of(env("TRACKER_STATIC_DIR", root().resolve("frontend").resolve("dist").toString()));
    }

    public static App fromEnv() {
        if (mode().equals("demo")) {
            return buildDemo(root().resolve(".tmp").resolve("demo-java").resolve("tracker.db"), env("TRACKER_TODAY", Dataset.DEMO_TODAY));
        }
        return buildJira(env("TRACKER_DB_URL", "sqlite:///" + data().resolve("tracker.db")), env("TRACKER_TODAY", null));
    }
}
