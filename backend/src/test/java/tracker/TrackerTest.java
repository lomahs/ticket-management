package tracker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The application without the demo profile (the Jira-site configuration) on an empty SQLite file of its own; every test
 * method gets a new context and database.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "tracker.static-dir=target/no-frontend",
        "tracker.data-dir=target/test-data",
        "tracker.secret-key=test-key",
        "tracker.admins=minh.hoang@acme.vn",
        "tracker.sync.check-every=PT1H",
        "spring.datasource.url=jdbc:sqlite:target/test-db/${random.uuid}.db?foreign_keys=on&journal_mode=WAL&busy_timeout=5000"
                + "&transaction_mode=IMMEDIATE"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public @interface TrackerTest {
}
