package tracker.adapters.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tracker.application.ports.UnitOfWork;
import tracker.application.ports.UnitOfWorkFactory;

/** Connection pool, schema migration and the unit of work. */
public final class Database implements UnitOfWorkFactory, AutoCloseable {
    private final String jdbcUrl;
    private final HikariDataSource dataSource;

    /**
     * @param url a SQLAlchemy-style URL ("sqlite:///path/to/tracker.db", as the original backend took) or a JDBC URL
     */
    public Database(String url) {
        this.jdbcUrl = toJdbc(url);
        if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            String file = jdbcUrl.substring("jdbc:sqlite:".length()).split("\\?", 2)[0];
            if (!file.isEmpty() && !file.equals(":memory:")) {
                try {
                    Path parent = Path.of(file).toAbsolutePath().getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot create the database directory for " + file, e);
                }
            }
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setPoolName("tracker-db");
        cfg.setMaximumPoolSize(jdbcUrl.startsWith("jdbc:sqlite:") ? 4 : 10);
        cfg.setAutoCommit(true);
        this.dataSource = new HikariDataSource(cfg);
    }

    /** sqlite:///relative.db, sqlite:////absolute.db and jdbc:… URLs. SQLite connections enforce foreign keys and use WAL. */
    static String toJdbc(String url) {
        String jdbc;
        if (url.startsWith("sqlite:///")) {
            jdbc = "jdbc:sqlite:" + url.substring("sqlite:///".length());
        } else if (url.startsWith("jdbc:")) {
            jdbc = url;
        } else {
            throw new IllegalArgumentException("Unsupported database URL " + url + " (use sqlite:///path or a jdbc: URL)");
        }
        if (jdbc.startsWith("jdbc:sqlite:") && !jdbc.contains("?")) {
            jdbc += "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000";
        }
        return jdbc;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Creates the tables when missing; safe to run on every start and on databases the Python backend created. */
    public void migrate() {
        String sql;
        try (InputStream in = Database.class.getResourceAsStream("/db/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("db/schema.sql is missing from the classpath");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            for (String stmt : sql.replaceAll("(?m)^--.*$", "").split(";\\s*\\n")) {
                if (!stmt.isBlank()) {
                    st.execute(stmt);
                }
            }
            c.commit();
            c.setAutoCommit(true);
        } catch (SQLException e) {
            throw new Jdbc.DataError(e);
        }
    }

    @Override
    public UnitOfWork begin() {
        try {
            return new SqlUnitOfWork(dataSource.getConnection(), jdbcUrl.startsWith("jdbc:sqlite:"));
        } catch (SQLException e) {
            throw new Jdbc.DataError(e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
