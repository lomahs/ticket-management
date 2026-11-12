package tracker.adapters.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.application.ports.SettingsRepository;
import tracker.application.settings.TrackerSettings;

/** The settings as one JSON document (the application's JSON settings: snake_case names). */
@Repository
class JdbcSettingsRepository implements SettingsRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcSettingsRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public TrackerSettings load() {
        return jdbc.sql("SELECT value FROM settings WHERE id = 1").query(String.class).optional().map(v -> {
            try {
                return json.readValue(v, TrackerSettings.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("The stored settings are not valid JSON", e);
            }
        }).orElseGet(TrackerSettings::new);
    }

    @Override
    public void save(TrackerSettings settings) {
        String value;
        try {
            value = json.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.sql("INSERT INTO settings (id, value) VALUES (1, :value) ON CONFLICT (id) DO UPDATE SET value = excluded.value")
                .param("value", value).update();
    }
}
