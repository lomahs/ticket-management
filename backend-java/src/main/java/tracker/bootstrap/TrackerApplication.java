package tracker.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tracker.adapters.SyncScheduler;
import tracker.adapters.api.StaticFrontend.StaticDir;
import tracker.application.Deps;

/**
 * Team Tracker API (Spring Boot). Run: {@code ./mvnw spring-boot:run} or {@code java -jar target/tracker.jar}
 * from the backend-java directory; see {@link Bootstrap} for the environment it reads.
 */
@SpringBootApplication(scanBasePackages = "tracker.adapters.api", exclude = DataSourceAutoConfiguration.class)
public class TrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackerApplication.class, args);
    }

    @Configuration
    public static class Wiring {
        @Bean(destroyMethod = "")
        public Bootstrap.App trackerApp() {
            return Bootstrap.fromEnv();
        }

        @Bean
        public Deps deps(Bootstrap.App app) {
            return app.deps();
        }

        @Bean
        public StaticDir staticDir() {
            return new StaticDir(Bootstrap.staticDir());
        }

        /** Closes the connection pool on shutdown (after the scheduler stops). */
        @Bean(destroyMethod = "close")
        public AutoCloseable database(Bootstrap.App app) {
            return app.db();
        }

        /** The periodic sync runs in Jira mode only, as in the original backend. */
        @Bean(destroyMethod = "close")
        public AutoCloseable syncScheduler(Bootstrap.App app) {
            return Bootstrap.mode().equals("demo") ? () -> { } : SyncScheduler.start(app.deps());
        }
    }
}
