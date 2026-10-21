package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.DemoTest;

/** The allocation endpoints over HTTP; `an` is a BA of PAY-1100. */
@DemoTest
@AutoConfigureMockMvc
@DirtiesContext
class ApiAllocationTest extends ApiTestSupport {
    static final String AN = "an.nguyen@acme.vn";

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM allocations").update();
    }

    @Test
    void baCannotRead() throws Exception {
        problem(call(get("/api/v1/people/binh/allocations").with(as(AN))), 403, "forbidden");
        problem(call(json(put("/api/v1/people/binh/allocations"), Map.of("from", "2026-10-01", "hours", Map.of("PAY-1100", 5))).with(as(AN))),
                403, "forbidden");
    }

    @Test
    void putOverHttp() throws Exception {
        Res r = call(json(put("/api/v1/people/binh/allocations"), Map.of("from", "2026-10-01", "hours", Map.of("PAY-1100", 5))));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode body = r.json();
        assertThat(body.get("steps").get(0).get("hours").get("PAY-1100").asDouble()).isEqualTo(5.0);
        assertThat(call(get("/api/v1/people/binh/allocations")).json().get("steps")).hasSize(1);
    }

    @Test
    void badDateAndTotalAreRefused() throws Exception {
        problem(call(json(put("/api/v1/people/binh/allocations"), Map.of("from", "soon", "hours", Map.of()))), 400, "invalid");
        problem(call(json(put("/api/v1/people/binh/allocations"), Map.of("from", "2026-10-01", "hours", Map.of("PAY-1100", 9)))), 422,
                "invalid");
    }
}
