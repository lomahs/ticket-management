package tracker.adapters.api;

import org.springframework.test.web.servlet.MockMvc;

import tracker.application.Deps;

/** MockMvc over the API controllers, for tests outside this package. */
public final class ApiTestSupport {
    private ApiTestSupport() {
    }

    public static MockMvc mvc(Deps d) {
        return ApiTest.mvc(d);
    }
}
