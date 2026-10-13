package tracker.adapters.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The connection pool, from the usual {@code spring.datasource.*} settings. SQLite creates the database file but not its
 * directory, so the directory is created first; with {@code tracker.reset-database} (the demo) the file is deleted first.
 *
 * <p>The JDBC URL sets {@code transaction_mode=IMMEDIATE}: a transaction takes the write lock when it begins, so a
 * {@code @Transactional} method that reads and then writes cannot fail because another one committed in between.
 * Reads therefore run without a transaction (each statement sees the latest committed data).
 */
@Configuration(proxyBeanMethods = false)
class SqliteDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties, @Value("${tracker.reset-database:false}") boolean reset) {
        String url = properties.determineUrl();
        if (url != null && url.startsWith("jdbc:sqlite:") && !url.contains(":memory:")) {
            Path file = Path.of(url.substring("jdbc:sqlite:".length()).split("\\?", 2)[0]).toAbsolutePath();
            try {
                Files.createDirectories(file.getParent());
                if (reset) {
                    for (String suffix : new String[]{"", "-wal", "-shm"}) {
                        Files.deleteIfExists(Path.of(file + suffix));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot prepare the database file " + file, e);
            }
        }
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
