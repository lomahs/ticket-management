package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.service.AnkenMemberSeeder;

@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AnkenMemberSeederTest {
    @Autowired
    AnkenMemberSeeder seeder;
    @Autowired
    AnkenMemberRepository ankenMembers;
    @Autowired
    JdbcClient jdbc;

    @Test
    void seedsFromActivityOnce() {
        Map<String, Set<String>> all = ankenMembers.findAll();
        assertThat(all.values()).anyMatch(m -> m.contains("an"));
        assertThat(all.values()).anyMatch(m -> m.contains("tanaka"));
        assertThat(jdbc.sql("SELECT count(*) FROM anken_members WHERE added_by <> 'seed'").query(Integer.class).single()).isZero();
        assertThat(seeder.seedOnce()).isZero();
    }

    @Test
    void doesNothingWhenMarkedDone() {
        jdbc.sql("DELETE FROM anken_members").update();
        assertThat(seeder.seedOnce()).isZero();
        assertThat(ankenMembers.isEmpty()).isTrue();
    }
}
