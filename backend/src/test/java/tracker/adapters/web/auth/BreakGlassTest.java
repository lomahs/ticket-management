package tracker.adapters.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BreakGlassTest {
    static final String HASH = "$2a$10$" + "a".repeat(53);

    @Test
    void unsetOrBlankIsOff() {
        assertThat(BreakGlass.of(null).enabled()).isFalse();
        assertThat(BreakGlass.of("  ").enabled()).isFalse();
    }

    @Test
    void aBcryptHashIsOn() {
        assertThat(BreakGlass.of(HASH).enabled()).isTrue();
        assertThat(BreakGlass.of(" " + HASH + "\n").passwordHash()).isEqualTo(HASH);
        assertThat(BreakGlass.of("$2y$12$" + "./AZaz09".repeat(6) + "abcde").enabled()).isTrue();
        assertThat(BreakGlass.of("$2$10$" + "b".repeat(53)).enabled()).isTrue();
    }

    @Test
    void aMalformedHashFailsWithoutShowingIt() {
        for (String bad : new String[] {"secret-password", "$2a$10$" + "a".repeat(52), "$2a$10$" + "a".repeat(54), "$2x$10$" + "a".repeat(53),
                "$2a$1$" + "a".repeat(53), "$2a$10$" + "a".repeat(52) + "!"}) {
            assertThatThrownBy(() -> BreakGlass.of(bad)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TRACKER_BREAKGLASS_PASSWORD_HASH").satisfies(e -> assertThat(e.getMessage()).doesNotContain(bad));
        }
    }
}
