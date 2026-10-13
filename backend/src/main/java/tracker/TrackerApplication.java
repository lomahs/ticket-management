package tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Team Tracker API. Run from the backend directory: {@code ./mvnw spring-boot:run} or {@code java -jar target/tracker.jar};
 * the {@code demo} profile runs on a simulated Jira site with demo data.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackerApplication.class, args);
    }
}
