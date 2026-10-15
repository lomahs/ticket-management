package tracker.application.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tracker.domain.Naming;
import tracker.domain.StepKind;
import tracker.domain.Thresholds;
import tracker.domain.WorkingHours;
import tracker.domain.WorkStructure;

/** Tracker settings: what the admin configures, stored as JSON documents by key (see {@link SettingsDocuments}). */
public class TrackerSettings {
    public JiraConnection jira = new JiraConnection();
    public FieldMapping fieldMapping = new FieldMapping();
    public NameRules nameRules = new NameRules();
    public StatusMapping statusMapping = new StatusMapping();
    public Thresholds thresholds = Thresholds.defaults();
    public WorkingHours workingHours = WorkingHours.defaults();
    public String defaultCountry = "VN";
    /** Phases in order and the fixed set of Tasks under every epic. */
    public WorkStructure structure = WorkStructure.empty();

    public static class JiraConnection {
        public String url = "";
        public String email = "";
        public String tokenEncrypted = "";
        /** Minutes, or "manual". */
        public String interval = "15";
        public List<String> epics = new ArrayList<>();
        /** Last connection test: ok | fail | null. */
        public String status = null;

        public JiraConnection() {
        }

        public JiraConnection(String url, String email, String tokenEncrypted, String interval, List<String> epics, String status) {
            this.url = url;
            this.email = email;
            this.tokenEncrypted = tokenEncrypted;
            this.interval = interval;
            this.epics = new ArrayList<>(epics);
            this.status = status;
        }
    }

    public static class FieldMapping {
        public String plannedStart = "customfield_10020";
        public String plannedEnd = "customfield_10016";
        public String effort = "timeoriginalestimate";

        /** The mapped field id of a target (planned_start | planned_end | effort). */
        public String get(String target) {
            return switch (target) {
                case "planned_start" -> plannedStart;
                case "planned_end" -> plannedEnd;
                case "effort" -> effort;
                default -> throw new IllegalArgumentException(target);
            };
        }

        public void set(String target, String fieldId) {
            switch (target) {
                case "planned_start" -> plannedStart = fieldId;
                case "planned_end" -> plannedEnd = fieldId;
                case "effort" -> effort = fieldId;
                default -> throw new IllegalArgumentException(target);
            }
        }
    }

    public static class NameRules {
        public String rule = Naming.DEFAULT_RULE;
        public Map<StepKind, String> aliases = new LinkedHashMap<>(Naming.DEFAULT_ALIASES);
    }

    public static class StatusMapping {
        /** Jira status name → N/W/R/C. */
        public Map<String, String> byName = new LinkedHashMap<>();
        /** Jira status name → category, recorded by sync. */
        public Map<String, String> seen = new LinkedHashMap<>();

        public StatusMapping() {
        }

        public StatusMapping(Map<String, String> byName) {
            this.byName = new LinkedHashMap<>(byName);
        }
    }
}
