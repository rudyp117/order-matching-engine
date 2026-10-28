package dev.tape.api;

import dev.tape.ExchangeApplication;
import dev.tape.application.ExchangeService;
import dev.tape.domain.Side;
import dev.tape.support.MemoryJournal;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.time.Clock;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExchangeControllerTest {
    private MockMvc mvc;
    private ExchangeService service;
    private MemoryJournal journal;
    private LocalValidatorFactoryBean validator;
    @BeforeEach void setup() {
        journal = new MemoryJournal();
        service = new ExchangeService(journal, UUID::randomUUID, Clock.systemUTC());
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ExchangeController(service))
                .setControllerAdvice(new ApiErrors()).setValidator(validator)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(ExchangeApplication.strictJsonMapper())).build();
    }
    @AfterEach void close() { validator.close(); }
    private ResultActions postOrder(String body) throws Exception {
        return mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body));
    }
    @Test void submitReadBookAndMetrics() throws Exception {
        String json = postOrder("{\"side\":\"BUY\",\"priceTicks\":10025,\"quantity\":10}")
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.order.status").value("OPEN"))
                .andExpect(jsonPath("$.order.cumulativeFilledQuantity").value(0))
                .andExpect(jsonPath("$.order.sequence").value(1)).andReturn().getResponse().getContentAsString();
        String id = ExchangeApplication.strictJsonMapper().readTree(json).path("order").path("id").asString();
        mvc.perform(get("/api/orders/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.remainingQuantity").value(10));
        mvc.perform(get("/api/book")).andExpect(status().isOk()).andExpect(jsonPath("$.bids[0].priceTicks").value(10025));
        mvc.perform(get("/api/metrics")).andExpect(status().isOk()).andExpect(jsonPath("$.acceptedOrders").value(1));
    }
    @Test void fillAndPersistedHistory() throws Exception {
        service.submit(Side.SELL, 100, 4);
        postOrder("{\"side\":\"BUY\",\"priceTicks\":105,\"quantity\":2}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.executions[0].priceTicks").value(100))
                .andExpect(jsonPath("$.order.status").value("FILLED"));
        mvc.perform(get("/api/trades")).andExpect(status().isOk()).andExpect(jsonPath("$[0].quantity").value(2));
    }
    @Test void cancelAndRejectTerminalTransition() throws Exception {
        UUID id = service.submit(Side.BUY, 100, 4).order().id();
        mvc.perform(delete("/api/orders/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.order.status").value("CANCELLED"));
        mvc.perform(delete("/api/orders/" + id)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }
    @Test void missingOrderVersusBadId() throws Exception {
        mvc.perform(get("/api/orders/" + UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(delete("/api/orders/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/nope")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
    @Test void invalidSideMissingFieldsAndUnknownFields() throws Exception {
        for (String body : new String[]{"{}", "{\"side\":\"HOLD\",\"priceTicks\":100,\"quantity\":1}",
                "{\"side\":0,\"priceTicks\":100,\"quantity\":1}",
                "{\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":1,\"id\":\"client-id\"}", "{"}) {
            postOrder(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }
    @Test void boundsAndStrictIntegerJson() throws Exception {
        for (String price : new String[]{"0", "-1", "100000001", "2147483648", "1.5", "\"100\"", "null"}) {
            postOrder("{\"side\":\"BUY\",\"priceTicks\":" + price + ",\"quantity\":1}")
                    .andExpect(status().isBadRequest());
        }
        postOrder("{\"side\":\"BUY\",\"priceTicks\":1,\"quantity\":1000001}").andExpect(status().isBadRequest());
    }
    @Test void boundedQueryParameters() throws Exception {
        for (String value : new String[]{"0", "101", "bad", "1.2"}) mvc.perform(get("/api/book?depth=" + value)).andExpect(status().isBadRequest());
        for (String value : new String[]{"0", "201", "bad"}) mvc.perform(get("/api/trades?limit=" + value)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/book?depth=100")).andExpect(status().isOk());
        mvc.perform(get("/api/trades?limit=200")).andExpect(status().isOk());
    }
    @Test void databaseFailureIsNotAcknowledgedAndHealthStateFences() throws Exception {
        journal.failBeforeCommit = true;
        postOrder("{\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":1}")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("UNAVAILABLE"));
        mvc.perform(get("/api/book")).andExpect(status().isServiceUnavailable());
        Assertions.assertFalse(service.available());
        Assertions.assertTrue(journal.entries.isEmpty());
    }
    @Test void contentTypeAndMethodHaveConsistentErrors() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(put("/api/orders")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
