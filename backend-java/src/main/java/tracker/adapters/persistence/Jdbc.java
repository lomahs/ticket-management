package tracker.adapters.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Thin JDBC helpers over one connection; SQLException becomes an unchecked DataError. */
final class Jdbc {
    private final Connection c;
    private final Runnable beforeWrite;

    /** @param beforeWrite runs before every statement that changes data (the unit of work opens its transaction) */
    Jdbc(Connection c, Runnable beforeWrite) {
        this.c = c;
        this.beforeWrite = beforeWrite;
    }

    @FunctionalInterface
    interface Row<T> {
        T map(ResultSet rs) throws SQLException;
    }

    static final class DataError extends RuntimeException {
        DataError(SQLException e) {
            super(e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof Boolean b) {
                ps.setInt(i + 1, b ? 1 : 0);
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    <T> List<T> query(String sql, Row<T> row, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(row.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DataError(e);
        }
    }

    <T> T first(String sql, Row<T> row, Object... params) {
        List<T> rows = query(sql, row, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    int update(String sql, Object... params) {
        beforeWrite.run();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataError(e);
        }
    }

    int insertReturningId(String sql, Object... params) {
        beforeWrite.run();
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("insert returned no primary key");
                }
                return keys.getInt(1);
            }
        } catch (SQLException e) {
            throw new DataError(e);
        }
    }

    void batch(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        beforeWrite.run();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Object[] r : rows) {
                bind(ps, r);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new DataError(e);
        }
    }

    /** "?, ?, ?" for an IN clause. */
    static String marks(Collection<?> values) {
        return String.join(", ", java.util.Collections.nCopies(values.size(), "?"));
    }

}
