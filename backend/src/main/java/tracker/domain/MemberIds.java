package tracker.domain;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;

/** How a member's id and colour are derived from a name. */
public final class MemberIds {
    private static final List<String> COLORS = List.of(
            "oklch(0.55 0.13 255)", "oklch(0.52 0.13 305)", "oklch(0.56 0.14 35)", "oklch(0.52 0.11 150)",
            "oklch(0.5 0.1 210)", "oklch(0.48 0.04 260)", "oklch(0.55 0.15 0)", "oklch(0.5 0.12 100)");

    private MemberIds() {
    }

    public static String slugify(String name) {
        String nfd = Normalizer.normalize(name, Normalizer.Form.NFD);
        StringBuilder folded = new StringBuilder();
        nfd.codePoints().filter(c -> Character.getType(c) != Character.NON_SPACING_MARK).forEach(folded::appendCodePoint);
        String s = folded.toString().replace("đ", "d").replace("Đ", "D").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "member" : s;
    }

    public static String color(String memberId) {
        CRC32 crc = new CRC32();
        crc.update(memberId.getBytes(StandardCharsets.UTF_8));
        return COLORS.get((int) (crc.getValue() % COLORS.size()));
    }

    /** {@code base}, else {@code base-2}, {@code base-3}, ... the first not in {@code taken}. */
    public static String newId(String base, Set<String> taken) {
        String id = base;
        for (int n = 2; taken.contains(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }
}
