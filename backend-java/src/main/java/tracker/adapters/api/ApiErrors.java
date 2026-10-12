package tracker.adapters.api;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tracker.application.AppError;
import tracker.application.ports.JiraAuthError;
import tracker.application.ports.JiraError;

/** Maps errors to problem+json, as the original API did; unknown routes answer {"detail": "Not Found"}. */
@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(AppError.class)
    public ResponseEntity<Object> app(AppError e) {
        return Problem.of(e.status(), e.code(), e.detail(),
                e.field() != null ? List.of(new RequestInvalid.FieldError(e.field(), e.detail())) : List.of());
    }

    @ExceptionHandler(RequestInvalid.class)
    public ResponseEntity<Object> invalid(RequestInvalid e) {
        return Problem.of(422, "invalid", e.getMessage(), e.errors());
    }

    @ExceptionHandler(JiraAuthError.class)
    public ResponseEntity<Object> jiraAuth(JiraAuthError e) {
        return Problem.of(502, "jira_auth", e.getMessage(), List.of());
    }

    @ExceptionHandler(JiraError.class)
    public ResponseEntity<Object> jira(JiraError e) {
        return Problem.of(502, "jira_unavailable", e.getMessage(), List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> tooLarge(MaxUploadSizeExceededException e) {
        String message = "The file is larger than 5 MB";
        return Problem.of(422, "invalid", message, List.of(new RequestInvalid.FieldError("file", message)));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Object> badUpload(MultipartException e) {
        String message = "Choose a .xlsx or .csv file";
        return Problem.of(422, "invalid", message, List.of(new RequestInvalid.FieldError("file", message)));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Object> notFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(Map.of("detail", "Not Found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("detail", "Method Not Allowed"));
    }

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<Object> mediaType(Exception e) {
        return Problem.of(422, "invalid", "Invalid request", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e) {
        log.error("unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_PLAIN).body("Internal Server Error");
    }
}
