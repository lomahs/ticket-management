package tracker.adapters.web;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;

import tracker.domain.Viewer;

/**
 * The OpenAPI document (served at /openapi.json) that the frontend's API types are generated from. Schemas follow the
 * JSON the API writes: snake_case names and enum values (the application's ObjectMapper), {@code @Nullable} components as
 * "T | null", and every property of a response as required (Jackson writes them all).
 */
@Configuration
public class OpenApiConfig {
    static {
        // the signed-in Viewer is a handler parameter resolved from the session, not part of the request
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Viewer.class);
    }

    @Bean
    public OpenAPI trackerOpenApi() {
        return new OpenAPI().info(new Info().title("Team Tracker API").version("1"));
    }

    @Bean
    public ModelResolver trackerModelResolver(ObjectMapper json) {
        return new TrackerModelResolver(json).openapi31(true);
    }

    @Bean
    public OpenApiCustomizer responsesRequireEveryProperty() {
        return api -> {
            Map<String, Schema> schemas = api.getComponents().getSchemas();
            Set<String> inputs = new HashSet<>();
            Set<String> outputs = new HashSet<>();
            api.getPaths().values().forEach(path -> path.readOperations().forEach(op -> {
                if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
                    op.getRequestBody().getContent().values().forEach(m -> referenced(m.getSchema(), schemas, inputs));
                }
                op.getResponses().values().forEach(r -> {
                    if (r.getContent() != null) {
                        r.getContent().values().forEach(m -> referenced(m.getSchema(), schemas, outputs));
                    }
                });
            }));
            api.setServers(null); // the document does not depend on where the server runs
            for (String name : outputs) {
                Schema<?> s = schemas.get(name);
                if (!inputs.contains(name) && s.getProperties() != null) {
                    s.setRequired(new ArrayList<>(s.getProperties().keySet()));
                }
            }
        };
    }

    /** Collects the names of the component schemas that {@code s} uses, directly or through other schemas. */
    private static void referenced(@Nullable Schema<?> s, Map<String, Schema> schemas, Set<String> out) {
        if (s == null) {
            return;
        }
        if (s.get$ref() != null) {
            String name = s.get$ref().substring(s.get$ref().lastIndexOf('/') + 1);
            if (out.add(name)) {
                referenced(schemas.get(name), schemas, out);
            }
            return;
        }
        if (s.getProperties() != null) {
            s.getProperties().values().forEach(p -> referenced(p, schemas, out));
        }
        referenced(s.getItems(), schemas, out);
        if (s.getAdditionalProperties() instanceof Schema<?> extra) {
            referenced(extra, schemas, out);
        }
        for (List<Schema> group : java.util.Arrays.asList(s.getAnyOf(), s.getOneOf(), s.getAllOf(), s.getPrefixItems())) {
            if (group != null) {
                group.forEach(g -> referenced(g, schemas, out));
            }
        }
    }

    /** Swagger's resolver with the application's JSON settings and JSpecify nullness for records. */
    static final class TrackerModelResolver extends ModelResolver {
        private static final PropertyNamingStrategies.NamingBase SNAKE =
                (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;

        TrackerModelResolver(ObjectMapper json) {
            super(json);
        }

        @Override
        public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> next) {
            Class<?> raw = _mapper.constructType(type.getType()).getRawClass();
            Schema<?> schema = super.resolve(type, context, next);
            if (schema != null && raw.isRecord()) {
                Schema<?> model = schema.get$ref() == null ? schema
                        : context.getDefinedModels().get(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
                if (model != null && model.getProperties() != null) {
                    markNullable(raw, model);
                }
            }
            return schema;
        }

        private static void markNullable(Class<?> record, Schema<?> model) {
            for (RecordComponent rc : record.getRecordComponents()) {
                String name = SNAKE.translate(rc.getName());
                Schema<?> p = model.getProperties().get(name);
                if (p == null) {
                    continue;
                }
                if (rc.getType() == Object.class) { // a JSON scalar read from Jira
                    p = new JsonSchema().types(new LinkedHashSet<>(List.of("string", "number", "boolean")));
                    model.getProperties().put(name, p);
                }
                if (rc.getAnnotatedType() instanceof AnnotatedParameterizedType generic) {
                    java.lang.reflect.AnnotatedType[] args = generic.getAnnotatedActualTypeArguments();
                    if (args[args.length - 1].isAnnotationPresent(Nullable.class)) {
                        if (p.getItems() != null) {
                            p.setItems(nullable(p.getItems()));
                        } else if (p.getAdditionalProperties() instanceof Schema<?> extra) {
                            p.setAdditionalProperties(nullable(extra));
                        }
                    }
                }
                if (rc.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                    model.getProperties().put(name, nullable(p));
                }
            }
        }

        private static boolean isNullable(Schema<?> s) {
            return (s.getTypes() != null && s.getTypes().contains("null"))
                    || (s.getAnyOf() != null && s.getAnyOf().stream().anyMatch(OpenApiConfig.TrackerModelResolver::isNullable));
        }

        /** "T | null": a type list with "null", or anyOf [T, null] for a reference. */
        static Schema<?> nullable(Schema<?> s) {
            if (isNullable(s)) {
                return s;
            }
            if (s.get$ref() != null) {
                Schema<?> any = new JsonSchema();
                any.setAnyOf(new ArrayList<>(List.of(s, new JsonSchema().types(new LinkedHashSet<>(List.of("null"))))));
                return any;
            }
            if (s.getTypes() == null || s.getTypes().isEmpty()) {
                if (s.getType() == null) {
                    return s;
                }
                s.setTypes(new LinkedHashSet<>(List.of(s.getType())));
            }
            s.addType("null");
            return s;
        }
    }
}
