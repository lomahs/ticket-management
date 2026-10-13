package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Calls the API through MockMvc and checks problem details. */
abstract class ApiTestSupport {
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    record Res(int status, String contentType, String text) {
        JsonNode json() throws Exception {
            return JSON.readTree(text);
        }
    }

    Res call(RequestBuilder r) throws Exception {
        MockHttpServletResponse res = mvc.perform(r).andReturn().getResponse();
        return new Res(res.getStatus(), res.getContentType(), res.getContentAsString(StandardCharsets.UTF_8));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, Object body) throws Exception {
        return r.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body));
    }

    /** The response is a problem detail with this status and code. */
    static JsonNode problem(Res r, int status, String code) throws Exception {
        assertThat(r.status()).as(r.text()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        JsonNode body = r.json();
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        return body;
    }
}
