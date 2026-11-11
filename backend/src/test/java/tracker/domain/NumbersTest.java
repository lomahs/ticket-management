package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class NumbersTest {
    @Test
    void hoursAreKeptInHundredths() {
        assertThat(Numbers.hours(1.0 / 3)).isEqualTo(0.33);
        assertThat(Numbers.hours(1200.0 / 3600)).isEqualTo(0.33); // a 20-minute worklog
        assertThat(Numbers.hours(2.675)).isEqualTo(2.68);
        assertThat(Numbers.sum(0.1, 0.2)).isEqualTo(0.3);
        assertThat(Numbers.floorHours(8.0 / 3)).isEqualTo(2.66);
        assertThat(Numbers.floorHours(5.34)).isEqualTo(5.34); // not 5.33 from 533.9999…
    }

    @Test
    void splitAddsUpToTheTotal() {
        assertThat(Numbers.split(8, 3)).containsExactly(2.67, 2.67, 2.66);
        assertThat(Numbers.split(1, 3)).containsExactly(0.34, 0.33, 0.33);
        assertThat(Numbers.split(0.01, 2)).containsExactly(0.01, 0.0);
        assertThat(Arrays.stream(Numbers.split(10, 7)).map(h -> h * 100).sum()).isCloseTo(1000, offset(1e-9));
    }
}
