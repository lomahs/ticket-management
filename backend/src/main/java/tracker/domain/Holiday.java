package tracker.domain;

import java.util.List;

public record Holiday(String date, List<String> countries, String name) {

    public Holiday {
        countries = List.copyOf(countries);
    }
}
