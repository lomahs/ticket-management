package tracker.adapters.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import tracker.application.ports.Repositories.AnkenRepository;
import tracker.application.ports.Repositories.CalendarRepository;
import tracker.application.ports.Repositories.IssueRepository;
import tracker.application.ports.Repositories.MemberRepository;
import tracker.application.ports.Repositories.SettingsRepository;
import tracker.application.ports.Repositories.SyncRunRepository;
import tracker.application.ports.UnitOfWork;

/**
 * One transaction on one connection. Closing it without commit() rolls back.
 *
 * <p>On SQLite the transaction starts at the first write (BEGIN IMMEDIATE), as pysqlite did for the original backend:
 * reads before it see the latest committed data instead of pinning a snapshot, so a unit of work that reads and then
 * writes waits for the write lock rather than failing when another one committed in between. Other databases use a
 * plain JDBC transaction.
 */
public final class SqlUnitOfWork implements UnitOfWork {
    private final Connection conn;
    private final boolean lazyBegin;
    private boolean inTransaction;
    private final SqlRepositories.Members members;
    private final SqlRepositories.Issues issues;
    private final SqlRepositories.AnkenRepo anken;
    private final SqlRepositories.Calendar calendar;
    private final SqlRepositories.Settings settings;
    private final SqlRepositories.SyncRuns syncRuns;

    SqlUnitOfWork(Connection conn, boolean sqlite) {
        this.conn = conn;
        this.lazyBegin = sqlite;
        try {
            conn.setAutoCommit(sqlite);
        } catch (SQLException e) {
            throw new Jdbc.DataError(e);
        }
        Jdbc db = new Jdbc(conn, this::beginWrite);
        members = new SqlRepositories.Members(db);
        issues = new SqlRepositories.Issues(db);
        anken = new SqlRepositories.AnkenRepo(db);
        calendar = new SqlRepositories.Calendar(db);
        settings = new SqlRepositories.Settings(db);
        syncRuns = new SqlRepositories.SyncRuns(db);
    }

    @Override
    public MemberRepository members() {
        return members;
    }

    @Override
    public IssueRepository issues() {
        return issues;
    }

    @Override
    public AnkenRepository anken() {
        return anken;
    }

    @Override
    public CalendarRepository calendar() {
        return calendar;
    }

    @Override
    public SettingsRepository settings() {
        return settings;
    }

    @Override
    public SyncRunRepository syncRuns() {
        return syncRuns;
    }

    private void execute(String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new Jdbc.DataError(e);
        }
    }

    private void beginWrite() {
        if (lazyBegin && !inTransaction) {
            execute("BEGIN IMMEDIATE");
            inTransaction = true;
        }
    }

    @Override
    public void commit() {
        if (lazyBegin) {
            if (inTransaction) {
                execute("COMMIT");
                inTransaction = false;
            }
            return;
        }
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new Jdbc.DataError(e);
        }
    }

    @Override
    public void close() {
        try {
            if (lazyBegin) {
                if (inTransaction) {
                    execute("ROLLBACK");
                    inTransaction = false;
                }
            } else {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        } catch (RuntimeException | SQLException e) {
            // the connection is closed below either way
        }
        try {
            conn.close();
        } catch (SQLException e) {
            throw new Jdbc.DataError(e);
        }
    }
}
