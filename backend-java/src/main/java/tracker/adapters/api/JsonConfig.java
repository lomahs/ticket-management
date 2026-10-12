package tracker.adapters.api;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;

import tracker.domain.DateReason;

/**
 * JSON as the original API wrote it: snake_case names, enums by value, timestamps as "2026-10-07T09:00:00.123456Z",
 * and (date, reason) pairs as two-element arrays.
 */
@Configuration
public class JsonConfig {

    static final class InstantSerializer extends JsonSerializer<Instant> {
        private static final DateTimeFormatter BASE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(format(value));
        }

        static String format(Instant value) {
            OffsetDateTime t = value.atOffset(ZoneOffset.UTC);
            int micros = t.get(ChronoField.MICRO_OF_SECOND);
            return t.format(BASE) + (micros != 0 ? String.format(".%06d", micros) : "") + "Z";
        }
    }

    static final class DateReasonSerializer extends JsonSerializer<DateReason> {
        @Override
        public void serialize(DateReason value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartArray();
            gen.writeString(value.date());
            gen.writeString(value.reason());
            gen.writeEndArray();
        }
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer trackerJson() {
        return b -> b
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .featuresToEnable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
                .serializerByType(Instant.class, new InstantSerializer())
                .serializerByType(DateReason.class, new DateReasonSerializer());
    }
}
