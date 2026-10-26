package tracker.application.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import tracker.domain.Naming;
import tracker.domain.StepKind;
import tracker.domain.TaskTemplate;
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
    /** When the Daily Overview of every tracked anken is captured on working days: "HH:mm" in the team's time zone, or "off". */
    public String dailyCapture = "18:00";
    /** Phases in order and the fixed set of Tasks under every epic. */
    public WorkStructure structure = WorkStructure.empty();
    /** Per tracked epic: its own Phases & Tasks and name rules. {@link #structure} and {@link #nameRules} are the default for new anken. */
    public Map<String, AnkenSettings> anken = new LinkedHashMap<>();

    /** The Phases & Tasks and the name rules of one anken. */
    public static class AnkenSettings {
        public WorkStructure structure = WorkStructure.empty();
        public NameRules nameRules = new NameRules();

        /** A deep copy: editing the copy never touches the source. */
        public static AnkenSettings copyOf(WorkStructure structure, NameRules nameRules) {
            AnkenSettings a = new AnkenSettings();
            a.structure = new WorkStructure(new ArrayList<>(structure.phases()),
                    structure.tasks().stream().map(t -> new TaskTemplate(t.match(), t.phase(), t.prefix(), t.workflow(), t.type())).toList());
            a.nameRules = new NameRules();
            a.nameRules.rule = nameRules.rule;
            a.nameRules.aliases = new LinkedHashMap<>(nameRules.aliases);
            return a;
        }
    }

    /** The Phases & Tasks of an epic; the default when the epic is null, blank or has none of its own. */
    public WorkStructure structureOf(@Nullable String epic) {
        AnkenSettings a = epic == null || epic.isBlank() ? null : anken.get(epic);
        return a == null ? structure : a.structure;
    }

    /** The name rules of an epic; the default when the epic is null, blank or has none of its own. */
    public NameRules nameRulesOf(@Nullable String epic) {
        AnkenSettings a = epic == null || epic.isBlank() ? null : anken.get(epic);
        return a == null ? nameRules : a.nameRules;
    }

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
        public String remaining = "timeestimate";

        /** The mapped field id of a target (planned_start | planned_end | effort | remaining). */
        public String get(String target) {
            return switch (target) {
                case "planned_start" -> plannedStart;
                case "planned_end" -> plannedEnd;
                case "effort" -> effort;
                case "remaining" -> remaining;
                default -> throw new IllegalArgumentException(target);
            };
        }

        public void set(String target, String fieldId) {
            switch (target) {
                case "planned_start" -> plannedStart = fieldId;
                case "planned_end" -> plannedEnd = fieldId;
                case "effort" -> effort = fieldId;
                case "remaining" -> remaining = fieldId;
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
