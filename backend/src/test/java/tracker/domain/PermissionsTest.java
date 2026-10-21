package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionsTest {
    @Test void pmAndPimWriteEverything() {
        for (Role r : List.of(Role.PM, Role.PIM))
            assertThat(Permissions.defaults(r)).allSatisfy((f, l) -> assertThat(l).isEqualTo(Level.WRITE));
    }
    @Test void baBalAndNoRoleReadEverythingButMembers() {
        for (Role r : Arrays.asList(Role.BA, Role.BAL, null)) {
            Map<Feature, Level> d = Permissions.defaults(r);
            assertThat(d.get(Feature.MEMBERS)).isEqualTo(Level.NONE);
            assertThat(d).hasSize(Feature.values().length);
            Arrays.stream(Feature.values()).filter(f -> f != Feature.MEMBERS).forEach(f -> assertThat(d.get(f)).isEqualTo(Level.READ));
        }
    }
    @Test void jmHasNothing() { assertThat(Permissions.defaults(Role.JM)).allSatisfy((f, l) -> assertThat(l).isEqualTo(Level.NONE)); }
    @Test void overridesWinOverTheRoleDefault() {
        Map<Feature, Level> e = Permissions.effective(Role.BA, Map.of(Feature.PLANNING, Level.WRITE, Feature.EFFORT, Level.NONE));
        assertThat(e.get(Feature.PLANNING)).isEqualTo(Level.WRITE);
        assertThat(e.get(Feature.EFFORT)).isEqualTo(Level.NONE);
        assertThat(e.get(Feature.MATRIX)).isEqualTo(Level.READ);
    }
    @Test void viewerCan() {
        Viewer ba = new Viewer("an@x", "An", "an", Role.BA, false, AccessStatus.ACTIVE, Permissions.defaults(Role.BA));
        assertThat(ba.can(Feature.MATRIX, Level.READ)).isTrue();
        assertThat(ba.can(Feature.MATRIX, Level.WRITE)).isFalse();
        Viewer admin = new Viewer("m@x", "M", null, null, true, AccessStatus.ACTIVE, Permissions.defaults(Role.JM));
        assertThat(admin.can(Feature.MEMBERS, Level.WRITE)).isTrue();
        Viewer off = new Viewer("m@x", "M", null, null, true, AccessStatus.DISABLED, Permissions.defaults(Role.PM));
        assertThat(off.can(Feature.MATRIX, Level.READ)).isFalse();
    }
    @Test void allAnkenByRoleAndSetting() {
        assertThat(Permissions.allAnken(Role.PM, AnkenScopeSetting.DEFAULT)).isTrue();
        assertThat(Permissions.allAnken(Role.PIM, AnkenScopeSetting.DEFAULT)).isTrue();
        assertThat(Permissions.allAnken(Role.BA, AnkenScopeSetting.ALL)).isTrue();
        assertThat(Permissions.allAnken(Role.BA, AnkenScopeSetting.DEFAULT)).isFalse();
        assertThat(Permissions.allAnken(null, AnkenScopeSetting.DEFAULT)).isFalse();
        assertThat(Permissions.allAnken(Role.PM, AnkenScopeSetting.MEMBERS)).isFalse();
    }
    @Test void viewerSees() {
        Map<Feature, Level> lv = Permissions.defaults(Role.BA);
        Viewer ba = new Viewer("an@x", "An", "an", Role.BA, false, AccessStatus.ACTIVE, lv, false, Set.of("PAY-1100"));
        assertThat(ba.sees("PAY-1100")).isTrue();
        assertThat(ba.sees("PAY-1300")).isFalse();
        Viewer all = new Viewer("an@x", "An", "an", Role.BA, false, AccessStatus.ACTIVE, lv, true, Set.of("PAY-1100"));
        assertThat(all.sees("PAY-1100")).isTrue();
        assertThat(all.sees("PAY-1300")).isTrue();
        assertThat(new Viewer("m@x", "M", null, null, true, AccessStatus.ACTIVE, lv).sees("ANY-1")).isTrue();
        assertThat(new Viewer("an@x", "An", "an", Role.BA, false, AccessStatus.DISABLED, lv, true, Set.of("PAY-1100")).sees("PAY-1100")).isFalse();
    }
    @Test void identityLowercasesTheEmail() { assertThat(new Identity(" An.Nguyen@ACME.vn ", "An").email()).isEqualTo("an.nguyen@acme.vn"); }
}
