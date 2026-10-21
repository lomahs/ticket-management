package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import tracker.DemoTest;
import tracker.application.service.AnkenSettingsInitializer;
import tracker.application.service.JiraSettingsService;
import tracker.application.settings.SettingsStore;
import tracker.application.settings.TrackerSettings;
import tracker.domain.WorkStructure;

@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AnkenSettingsTest {
    @Autowired
    SettingsStore store;
    @Autowired
    AnkenSettingsInitializer initializer;
    @Autowired
    JiraSettingsService jiraSettings;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    ApplicationContext context;

    private static final WorkStructure CUSTOM = new WorkStructure(List.of("Alpha", "Beta"), List.of());

    @Test
    void oldDocumentIsInitialized() throws Exception {
        store.update(s -> s.structure = CUSTOM);
        // an old database: the stored document has no "anken" key
        ObjectNode doc = (ObjectNode) json.readTree(jdbc.sql("SELECT value FROM settings WHERE id = 1").query(String.class).single());
        assertThat(doc.remove("anken")).isNotNull();
        jdbc.sql("UPDATE settings SET value = :v WHERE id = 1").param("v", json.writeValueAsString(doc)).update();
        assertThat(jdbc.sql("SELECT value FROM settings WHERE id = 1").query(String.class).single()).doesNotContain("\"anken\"");
        assertThat(store.load().anken).isEmpty();

        // start-up: the runner bean initializes every tracked epic, and runs before the member seeder
        ApplicationRunner init = context.getBean("ankenSettingsInit", ApplicationRunner.class);
        init.run(null);
        TrackerSettings s = store.load();
        assertThat(s.jira.epics).isNotEmpty();
        for (String e : s.jira.epics) {
            assertThat(s.structureOf(e)).isEqualTo(CUSTOM);
            assertThat(s.anken.get(e).nameRules.rule).isEqualTo(s.nameRules.rule);
        }
        assertThat(s.anken.keySet()).containsExactlyInAnyOrderElementsOf(s.jira.epics);
        assertThat(context.findAnnotationOnBean("ankenSettingsInit", Order.class).value())
                .isLessThan(context.findAnnotationOnBean("ankenMemberSeed", Order.class).value());
        assertThat(initializer.ensureAll()).isZero();
    }

    @Test
    void editingTheDefaultLeavesExistingAnkenAlone() {
        store.update(s -> s.anken.clear());
        initializer.ensureAll();
        List<String> before = store.load().structureOf("PAY-1100").phases();
        store.update(s -> s.structure = CUSTOM);
        TrackerSettings s = store.load();
        assertThat(s.structureOf("PAY-1100").phases()).isEqualTo(before).isNotEqualTo(CUSTOM.phases());
        assertThat(s.structureOf("NOPE-1")).isSameAs(s.structure);
    }

    @Test
    void addEpicCopiesTheDefault() {
        store.update(s -> s.structure = CUSTOM);
        jiraSettings.addEpic("PAY-1500");
        TrackerSettings s = store.load();
        assertThat(s.anken).containsKey("PAY-1500");
        assertThat(s.structureOf("PAY-1500")).isEqualTo(s.structure);
        assertThat(s.nameRulesOf("PAY-1500").rule).isEqualTo(s.nameRules.rule);
        assertThat(s.nameRulesOf("PAY-1500").aliases).isEqualTo(s.nameRules.aliases).isNotSameAs(s.nameRules.aliases);
    }

    @Test
    void unknownEpicFallsBackToTheDefault() {
        TrackerSettings s = store.load();
        assertThat(s.structureOf("NOPE-1")).isSameAs(s.structure);
        assertThat(s.structureOf(null)).isSameAs(s.structure);
        assertThat(s.structureOf(" ")).isSameAs(s.structure);
        assertThat(s.nameRulesOf("NOPE-1")).isSameAs(s.nameRules);
    }
}
