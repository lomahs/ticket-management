package tracker.adapters.web;

import org.springframework.http.HttpStatus;

/** A problem the web layer itself answers with (sign-in errors): its status, {@code code} and detail. */
public class ApiProblem extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
