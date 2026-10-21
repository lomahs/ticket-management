package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

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

    private static final WorkStructure CUSTOM = new WorkStructure(List.of("Alpha", "Beta"), List.of());

    @Test
    void oldDocumentIsInitialized() {
        store.update(s -> {
            s.anken.clear();
            s.structure = CUSTOM;
        });
        int epics = store.load().jira.epics.size();
        assertThat(epics).isPositive();
        assertThat(initializer.ensureAll()).isEqualTo(epics);
        TrackerSettings s = store.load();
        for (String e : s.jira.epics) {
            assertThat(s.structureOf(e).phases()).containsExactly("Alpha", "Beta");
            assertThat(s.nameRulesOf(e).rule).isEqualTo(s.nameRules.rule);
        }
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
