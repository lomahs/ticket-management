package tracker.adapters.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Request validation with the rules and messages of the original (pydantic) API, so forms show the same field errors:
 * every problem is collected with its dotted field path, then {@link #check()} throws them as one 422.
 */
final class Input {
    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}$";
    static final String MONTH = "^\\d{4}-\\d{2}$";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TRUE = Set.of("1", "on", "t", "true", "y", "yes");
    private static final Set<String> FALSE = Set.of("0", "off", "f", "false", "n", "no");

    private final List<RequestInvalid.FieldError> errors = new ArrayList<>();

    void error(String field, String message) {
        errors.add(new RequestInvalid.FieldError(field, message));
    }

    /** Throws the collected problems, if any. */
    void check() {
        if (!errors.isEmpty()) {
            throw new RequestInvalid(errors);
        }
    }

    static String path(String parent, Object child) {
        return parent == null || parent.isEmpty() ? String.valueOf(child) : parent + "." + child;
    }

    // --- messages ---------------------------------------------------------------------------------------------------

    static String choices(List<String> values) {
        List<String> quoted = values.stream().map(v -> "'" + v + "'").toList();
        if (quoted.size() == 1) {
            return quoted.get(0);
        }
        return String.join(", ", quoted.subList(0, quoted.size() - 1)) + " or " + quoted.get(quoted.size() - 1);
    }

    private static String num(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }

    // --- query and path parameters ----------------------------------------------------------------------------------

    /** A required parameter; null (and an error) when missing. */
    String required(String name, String value) {
        if (value == null) {
            error(name, "Field required");
        }
        return value;
    }

    String pattern(String name, String value, String regex) {
        String strict = regex.endsWith("$") ? regex.substring(0, regex.length() - 1) + "\\z" : regex; // "$" is end of text
        if (value != null && !Pattern.compile(strict).matcher(value).find()) {
            error(name, "String should match pattern '" + regex + "'");
        }
        return value;
    }

    String literal(String name, String value, List<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            error(name, "Input should be " + choices(allowed));
        }
        return value;
    }

    /** An integer parameter; null (and an error) when it does not parse. */
    Integer integer(String name, String value) {
        if (value == null) {
            return null;
        }
        String s = value.strip();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(s);
                if (d == Math.rint(d)) {
                    return (int) d;
                }
                error(name, "Input should be a valid integer, got a number with a fractional part");
            } catch (NumberFormatException e2) {
                error(name, "Input should be a valid integer, unable to parse string as an integer");
            }
            return null;
        }
    }

    void range(String name, Integer value, Double ge, Double le) {
        if (value == null) {
            return;
        }
        if (ge != null && value < ge) {
            error(name, "Input should be greater than or equal to " + num(ge));
        } else if (le != null && value > le) {
            error(name, "Input should be less than or equal to " + num(le));
        }
    }

    /** A boolean parameter; {@code dflt} when absent or invalid. */
    boolean bool(String name, String value, boolean dflt) {
        if (value == null) {
            return dflt;
        }
        String s = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (TRUE.contains(s)) {
            return true;
        }
        if (FALSE.contains(s)) {
            return false;
        }
        error(name, "Input should be a valid boolean, unable to interpret input");
        return dflt;
    }

    // --- JSON bodies ------------------------------------------------------------------------------------------------

    /** The body as an object node; null (and an error) when it is missing, not JSON or not an object. */
    Obj body(byte[] raw) {
        if (raw == null || raw.length == 0) {
            error(null, "Field required");
            return null;
        }
        JsonNode node;
        try {
            node = JSON.readTree(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            long at = e.getLocation() != null ? Math.max(0, e.getLocation().getCharOffset()) : 0; // like json's error pos
            error(String.valueOf(at), "JSON decode error");
            return null;
        } catch (IOException e) {
            error("0", "JSON decode error");
            return null;
        }
        if (node == null || node.isMissingNode()) {
            error(null, "Field required");
            return null;
        }
        return model(node, null);
    }

    /** {@code node} as a nested model; null (and an error) when it is not an object. */
    Obj model(JsonNode node, String path) {
        if (!node.isObject()) {
            error(path, "Input should be a valid dictionary or object to extract fields from");
            return null;
        }
        return new Obj(node, path);
    }

    /** One JSON object being validated. Getters record problems and return null for missing or invalid values. */
    final class Obj {
        private final JsonNode node;
        private final String path;

        Obj(JsonNode node, String path) {
            this.node = node;
            this.path = path;
        }

        String at(String name) {
            return path(path, name);
        }

        boolean has(String name) {
            return node.has(name);
        }

        JsonNode raw(String name) {
            return node.get(name);
        }

        private JsonNode present(String name) {
            JsonNode v = node.get(name);
            if (v == null) {
                error(at(name), "Field required");
            }
            return v;
        }

        /** Required, non-null string. */
        String str(String name) {
            JsonNode v = present(name);
            return v == null ? null : asString(v, at(name), false);
        }

        /** Optional string with a default; null is not allowed. */
        String str(String name, String dflt) {
            JsonNode v = node.get(name);
            return v == null ? dflt : asString(v, at(name), false);
        }

        /** Optional, nullable string (default null). */
        String nullableStr(String name) {
            JsonNode v = node.get(name);
            return v == null ? null : asString(v, at(name), true);
        }

        /** Required number. */
        Double number(String name) {
            JsonNode v = present(name);
            return v == null ? null : asNumber(v, at(name));
        }

        Double number(String name, double dflt) {
            JsonNode v = node.get(name);
            return v == null ? Double.valueOf(dflt) : asNumber(v, at(name));
        }

        Boolean bool(String name, boolean dflt) {
            JsonNode v = node.get(name);
            return v == null ? Boolean.valueOf(dflt) : asBool(v, at(name));
        }

        /** Required list; items converted by {@code item} (path, node) → value, null entries for invalid items. */
        <T> List<T> list(String name, BiFunction<String, JsonNode, T> item) {
            JsonNode v = present(name);
            return v == null ? null : asList(v, at(name), item);
        }

        <T> List<T> list(String name, List<T> dflt, BiFunction<String, JsonNode, T> item) {
            JsonNode v = node.get(name);
            return v == null ? dflt : asList(v, at(name), item);
        }

        /** Required object of string keys; values converted by {@code value} (path, node) → value. */
        <T> Map<String, T> dict(String name, BiFunction<String, JsonNode, T> value) {
            JsonNode v = present(name);
            return v == null ? null : asDict(v, at(name), value);
        }

        /** Optional, nullable object. */
        <T> Map<String, T> nullableDict(String name, BiFunction<String, JsonNode, T> value) {
            JsonNode v = node.get(name);
            return v == null || v.isNull() ? null : asDict(v, at(name), value);
        }
    }

    // --- value conversion (pydantic lax mode) -----------------------------------------------------------------------

    String asString(JsonNode v, String at, boolean nullable) {
        if (v.isNull() && nullable) {
            return null;
        }
        if (!v.isTextual()) {
            error(at, "Input should be a valid string");
            return null;
        }
        return v.asText();
    }

    Double asNumber(JsonNode v, String at) {
        if (v.isNumber()) {
            return v.isBigDecimal() ? new BigDecimal(v.asText()).doubleValue() : v.asDouble();
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? 1.0 : 0.0;
        }
        if (v.isTextual()) {
            try {
                return Double.parseDouble(v.asText().strip());
            } catch (NumberFormatException e) {
                error(at, "Input should be a valid number, unable to parse string as a number");
                return null;
            }
        }
        error(at, "Input should be a valid number");
        return null;
    }

    Integer asInt(JsonNode v, String at) {
        if (v.isIntegralNumber() || v.isBoolean()) {
            return v.isBoolean() ? (v.asBoolean() ? 1 : 0) : v.asInt();
        }
        if (v.isNumber()) {
            double d = v.asDouble();
            if (d == Math.rint(d)) {
                return (int) d;
            }
            error(at, "Input should be a valid integer, got a number with a fractional part");
            return null;
        }
        if (v.isTextual()) {
            return integer(at, v.asText());
        }
        error(at, "Input should be a valid integer");
        return null;
    }

    Boolean asBool(JsonNode v, String at) {
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isNumber()) {
            double d = v.asDouble();
            if (d == 0 || d == 1) {
                return d == 1;
            }
            error(at, "Input should be a valid boolean, unable to interpret input");
            return null;
        }
        if (v.isTextual()) {
            String s = v.asText().strip().toLowerCase(java.util.Locale.ROOT);
            if (TRUE.contains(s) || FALSE.contains(s)) {
                return TRUE.contains(s);
            }
            error(at, "Input should be a valid boolean, unable to interpret input");
            return null;
        }
        error(at, "Input should be a valid boolean");
        return null;
    }

    <T> List<T> asList(JsonNode v, String at, BiFunction<String, JsonNode, T> item) {
        if (!v.isArray()) {
            error(at, "Input should be a valid list");
            return null;
        }
        List<T> out = new ArrayList<>();
        for (int i = 0; i < v.size(); i++) {
            out.add(item.apply(path(at, i), v.get(i)));
        }
        return out;
    }

    <T> Map<String, T> asDict(JsonNode v, String at, BiFunction<String, JsonNode, T> value) {
        if (!v.isObject()) {
            error(at, "Input should be a valid dictionary");
            return null;
        }
        Map<String, T> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = v.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), value.apply(path(at, e.getKey()), e.getValue()));
        }
        return out;
    }

    /** A string that must be one of {@code allowed} (Literal or enum); nullable when asked. */
    String asChoice(JsonNode v, String at, List<String> allowed, boolean nullable) {
        if (v.isNull() && nullable) {
            return null;
        }
        if (v.isTextual() && allowed.contains(v.asText())) {
            return v.asText();
        }
        error(at, "Input should be " + choices(allowed));
        return null;
    }

    // --- constraints ------------------------------------------------------------------------------------------------

    void minLength(String at, String value, int min) {
        if (value != null && value.codePointCount(0, value.length()) < min) {
            error(at, "String should have at least " + min + " character" + (min == 1 ? "" : "s"));
        }
    }

    void maxLength(String at, String value, int max) {
        if (value != null && value.codePointCount(0, value.length()) > max) {
            error(at, "String should have at most " + max + " character" + (max == 1 ? "" : "s"));
        }
    }

    void minItems(String at, List<?> value, int min) {
        if (value != null && value.size() < min) {
            error(at, "List should have at least " + min + " item" + (min == 1 ? "" : "s") + " after validation, not " + value.size());
        }
    }

    void maxItems(String at, List<?> value, int max) {
        if (value != null && value.size() > max) {
            error(at, "List should have at most " + max + " item" + (max == 1 ? "" : "s") + " after validation, not " + value.size());
        }
    }

    void gt(String at, Double value, double bound) {
        if (value != null && !(value > bound)) {
            error(at, "Input should be greater than " + num(bound));
        }
    }

    void ge(String at, Double value, double bound) {
        if (value != null && !(value >= bound)) {
            error(at, "Input should be greater than or equal to " + num(bound));
        }
    }

    void le(String at, Double value, double bound) {
        if (value != null && !(value <= bound)) {
            error(at, "Input should be less than or equal to " + num(bound));
        }
    }
}
