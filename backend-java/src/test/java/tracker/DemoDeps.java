package tracker;

import java.nio.file.Path;
import java.util.List;

import tracker.adapters.clock.FixedClock;
import tracker.adapters.fake.Dataset;
import tracker.adapters.fake.FakeJira;
import tracker.adapters.persistence.Database;
import tracker.adapters.persistence.FernetSecretBox;
import tracker.application.Deps;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.ports.JiraReader;
import tracker.application.ports.UnitOfWork;

/** Use-case dependencies on a fresh SQLite database synced from the demo FakeJira. */
public final class DemoDeps {
    private DemoDeps() {
    }

    /** A site that rejects the credentials. */
    static final class BadReader implements JiraReader {
        @Override
        public Iterable<IssueSnapshot> search(List<String> epicKeys, java.time.Instant updatedSince) {
            return List.of();
        }

        @Override
        public EpicSnapshot getEpic(String key) {
            return null;
        }

        @Override
        public IssueSnapshot getIssue(String key) {
            return null;
        }

        @Override
        public List<JiraField> listFields() {
            return List.of();
        }

        @Override
        public boolean testConnection() {
            return false;
        }
    }

    public static Deps demo(Path tmp) {
        return demo(tmp, true);
    }

    public static Deps demo(Path tmp, boolean writable) {
        Database db = new Database("sqlite:///" + tmp.resolve("demo.db"));
        db.migrate();
        FernetSecretBox box = FernetSecretBox.fromKeyFile(tmp.resolve("secret.key"));
        try (UnitOfWork u = db.begin()) {
            FakeJira.seedTracker(u, box);
            u.commit();
        }
        FixedClock clock = new FixedClock(Dataset.DEMO_TODAY);
        FakeJira jira = new FakeJira(clock);
        Deps deps = new Deps(db, clock, jira, writable ? jira : null, box,
                (url, email, token) -> url.contains("atlassian.net") && token.length() > 8 ? jira : new BadReader(),
                writable ? "demo" : "jira");
        if (!deps.sync().run(true).status().equals("ok")) {
            throw new IllegalStateException("demo sync failed");
        }
        return deps;
    }
}
