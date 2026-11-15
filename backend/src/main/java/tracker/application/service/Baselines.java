package tracker.application.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.application.ports.PlanVersionRepository.SavedVersion;
import tracker.domain.Baseline;

/** A saved version's JSON body and the Baseline plan it holds under {@code "subtasks"}. */
public final class Baselines {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Baselines() {
    }

    /** {@code rest} with the sub-tasks added, as the body of a version with estimates. */
    public static String body(Map<String, Object> rest, List<Baseline.Item> items) {
        Map<String, Object> out = new LinkedHashMap<>(rest);
        List<Map<String, Object>> subtasks = new ArrayList<>();
        for (Baseline.Item i : items) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("key", i.key());
            o.put("estimate_h", i.estimateH());
            o.put("parent_key", i.parentKey());
            o.put("owner_id", i.ownerId());
            o.put("days", new TreeMap<>(i.days()));
            subtasks.add(o);
        }
        out.put("subtasks", subtasks);
        return write(out);
    }

    static String write(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Optional<Baseline> of(Optional<SavedVersion> version) {
        return version.map(v -> {
            JsonNode root;
            try {
                root = JSON.readTree(v.body());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            List<Baseline.Item> items = new ArrayList<>();
            for (JsonNode s : root.path("subtasks")) {
                Map<String, Double> days = new LinkedHashMap<>();
                s.path("days").fields().forEachRemaining(e -> days.put(e.getKey(), e.getValue().asDouble()));
                JsonNode parent = s.path("parent_key");
                items.add(new Baseline.Item(s.path("key").asText(), s.path("estimate_h").asDouble(),
                        parent.isNull() || parent.isMissingNode() ? null : parent.asText(), s.path("owner_id").asText(""), days));
            }
            return new Baseline(v.savedAt(), items);
        });
    }
}
