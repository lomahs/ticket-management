package tracker.adapters.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.Errors;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import tracker.application.AppError;
import tracker.application.ports.JiraAuthError;
import tracker.application.ports.JiraError;
import tracker.application.ports.JiraRejected;

/**
 * Errors as RFC 9457 problem details. Besides the standard members every problem has {@code code} (a stable name the UI
 * can test) and {@code errors}: the problems with single request fields, as {@code {field, message}} where the field is the
 * snake_case JSON path ("overrides.1.start") or the query/path parameter name.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final PropertyNamingStrategies.NamingBase SNAKE =
            (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;

    /** A problem with one request field; {@code field} is null for the request as a whole. */
    public record FieldError(@Nullable String field, String message) {
    }

    public static ProblemDetail problem(HttpStatusCode status, String code, String detail, List<FieldError> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("code", code);
        p.setProperty("errors", errors);
        return p;
    }

    private static ResponseEntity<Object> invalid(List<FieldError> found) {
        List<FieldError> errors = new ArrayList<>(found);
        errors.sort(Comparator.comparing(FieldError::field, Comparator.nullsFirst(Comparator.naturalOrder())));
        FieldError first = errors.isEmpty() ? new FieldError(null, "Invalid request") : errors.get(0);
        String detail = first.field() == null ? first.message() : first.field() + ": " + first.message();
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "invalid", detail, errors));
    }

    // --- business rules ---------------------------------------------------------------------------------------------

    @ExceptionHandler(AppError.class)
    ResponseEntity<ProblemDetail> appError(AppError e) {
        record Kind(HttpStatus status, String code) {
        }
        Kind kind = switch (e) {
            case AppError.NotFound n -> new Kind(HttpStatus.NOT_FOUND, "not_found");
            case AppError.Conflict c -> new Kind(HttpStatus.CONFLICT, "conflict");
            case AppError.Unauthenticated u -> new Kind(HttpStatus.UNAUTHORIZED, "unauthenticated");
            case AppError.NoAccess n -> new Kind(HttpStatus.FORBIDDEN, "no_access");
            case AppError.Forbidden f -> new Kind(HttpStatus.FORBIDDEN, "forbidden");
            case AppError.CapabilityDisabled c -> new Kind(HttpStatus.FORBIDDEN, "capability_disabled");
            default -> new Kind(HttpStatus.UNPROCESSABLE_ENTITY, "invalid");
        };
        List<FieldError> errors = e.field() != null ? List.of(new FieldError(e.field(), e.getMessage())) : List.of();
        return ResponseEntity.status(kind.status()).body(problem(kind.status(), kind.code(), e.getMessage(), errors));
    }

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ProblemDetail> apiProblem(ApiProblem e) {
        return ResponseEntity.status(e.status()).body(problem(e.status(), e.code(), e.getMessage(), List.of()));
    }

    @ExceptionHandler(JiraAuthError.class)
    ResponseEntity<ProblemDetail> jiraAuth(JiraAuthError e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem(HttpStatus.BAD_GATEWAY, "jira_auth", e.getMessage(), List.of()));
    }

    /** Jira refused a write: the request does not suit the site (a field not on the screen, a workflow rule…). */
    @ExceptionHandler(JiraRejected.class)
    ResponseEntity<ProblemDetail> jiraRejected(JiraRejected e) {
        return ResponseEntity.unprocessableEntity()
                .body(problem(HttpStatus.UNPROCESSABLE_ENTITY, "jira_rejected", e.getMessage(), List.of()));
    }

    @ExceptionHandler(JiraError.class)
    ResponseEntity<ProblemDetail> jira(JiraError e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(problem(HttpStatus.BAD_GATEWAY, "jira_unavailable", e.getMessage(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("unhandled error", e);
        return ResponseEntity.internalServerError()
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Something went wrong on the server", List.of()));
    }

    // --- request validation (Bean Validation, binding, JSON) --------------------------------------------------------

    /** "overrides[0].startDate" → "overrides.0.start_date"; map keys in brackets are kept as they are. */
    static String jsonPath(String beanPath) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < beanPath.length()) {
            char c = beanPath.charAt(i);
            if (c == '[') {
                int end = beanPath.indexOf(']', i);
                out.append(out.isEmpty() ? "" : ".").append(beanPath, i + 1, end);
                i = end + 1;
            } else if (c == '.') {
                i++;
            } else {
                int end = i;
                while (end < beanPath.length() && beanPath.charAt(end) != '.' && beanPath.charAt(end) != '[') {
                    end++;
                }
                out.append(out.isEmpty() ? "" : ".").append(SNAKE.translate(beanPath.substring(i, end)));
                i = end;
            }
        }
        return out.toString();
    }

    private static List<FieldError> fieldErrors(Errors errors) {
        List<FieldError> out = new ArrayList<>();
        errors.getFieldErrors().forEach(f -> out.add(new FieldError(jsonPath(f.getField()), String.valueOf(f.getDefaultMessage()))));
        errors.getGlobalErrors().forEach(g -> out.add(new FieldError(null, String.valueOf(g.getDefaultMessage()))));
        return out;
    }

    /** The name a parameter has in the request: its query or path name. */
    private static String requestName(MethodParameter p) {
        RequestParam q = p.getParameterAnnotation(RequestParam.class);
        if (q != null && !q.name().isEmpty()) {
            return q.name();
        }
        PathVariable v = p.getParameterAnnotation(PathVariable.class);
        if (v != null && !v.name().isEmpty()) {
            return v.name();
        }
        return SNAKE.translate(String.valueOf(p.getParameterName()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return invalid(fieldErrors(e.getBindingResult()));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e, HttpHeaders headers,
                                                                            HttpStatusCode status, WebRequest request) {
        List<FieldError> out = new ArrayList<>();
        e.getParameterValidationResults().forEach(r -> {
            if (r instanceof ParameterErrors body) {
                out.addAll(fieldErrors(body));
            } else {
                String name = requestName(r.getMethodParameter());
                r.getResolvableErrors().forEach(m -> out.add(new FieldError(name, String.valueOf(m.getDefaultMessage()))));
            }
        });
        return invalid(out);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException e,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return invalid(List.of(new FieldError(e.getParameterName(), "is required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> parameterType(MethodArgumentTypeMismatchException e) {
        Class<?> type = e.getRequiredType();
        String message = type == int.class || type == Integer.class ? "must be an integer"
                : type == boolean.class || type == Boolean.class ? "must be true or false" : "has an invalid value";
        return invalid(List.of(new FieldError(requestName(e.getParameter()), message)));
    }

    /** A body that is missing, is not JSON, or holds a value of the wrong type. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        if (e.getCause() instanceof JsonMappingException m && !m.getPath().isEmpty()) {
            StringBuilder path = new StringBuilder();
            for (JsonMappingException.Reference ref : m.getPath()) {
                path.append(path.isEmpty() ? "" : ".").append(ref.getFieldName() != null ? ref.getFieldName() : ref.getIndex());
            }
            String message = "has an invalid value";
            if (m instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException f && f.getTargetType() != null
                    && f.getTargetType().isEnum()) {
                List<String> values = java.util.Arrays.stream(f.getTargetType().getEnumConstants()).map(String::valueOf).toList();
                message = "must be one of: " + String.join(", ", values);
            }
            return invalid(List.of(new FieldError(path.toString(), message)));
        }
        String message = e.getCause() == null ? "A request body is required"
                : e.getCause() instanceof com.fasterxml.jackson.core.JsonParseException ? "The body is not valid JSON"
                : "The body must be a JSON object";
        return invalid(List.of(new FieldError(null, message)));
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e, HttpHeaders headers,
                                                                          HttpStatusCode status, WebRequest request) {
        String message = "The file is larger than 5 MB";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(problem(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", message, List.of(new FieldError("file", message))));
    }

    /** The standard problems of Spring MVC (404, 405, 415, …) get the {@code code} and {@code errors} members too. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail p && (p.getProperties() == null || !p.getProperties().containsKey("code"))) {
            Map<String, Object> props = new LinkedHashMap<>();
            HttpStatus known = HttpStatus.resolve(status.value());
            props.put("code", known != null ? known.name().toLowerCase(java.util.Locale.ROOT) : "error");
            props.put("errors", List.of());
            props.forEach(p::setProperty);
        }
        return super.createResponseEntity(body, headers, status, request);
    }
}
