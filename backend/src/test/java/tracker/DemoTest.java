package tracker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The application in the demo profile (simulated Jira, demo data synced through the real pipeline) on its own SQLite file.
 * Test classes with the same configuration share one context; classes that write add {@code @DirtiesContext}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "tracker.static-dir=target/no-frontend",
        "spring.datasource.url=jdbc:sqlite:target/test-db/${random.uuid}.db?foreign_keys=on&journal_mode=WAL&busy_timeout=5000"
                + "&transaction_mode=IMMEDIATE"})
@ActiveProfiles("demo")
public @interface DemoTest {
}
