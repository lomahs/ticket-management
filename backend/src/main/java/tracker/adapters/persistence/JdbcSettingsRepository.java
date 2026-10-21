package tracker.adapters.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import tracker.application.ports.SettingsRepository;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Thresholds;

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
                JsonNode doc = json.readTree(v);
                JsonNode thresholds = doc.get("thresholds");
                // a document written before the effort threshold existed: 1 hour, not 0
                if (thresholds instanceof ObjectNode t && !t.has("effort_diff_h")) {
                    t.put("effort_diff_h", Thresholds.defaults().effortDiffH());
                }
                return json.treeToValue(doc, TrackerSettings.class);
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
