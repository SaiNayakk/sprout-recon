package app.sprout.recon;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import app.sprout.recon.domain.Runs;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reconciliation on a real Postgres against stand-ins for every book it reads, which the tests make
 * agree, disagree, disagree only for a moment (money in flight), or go unreadable.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.config.name=recon", "sprout.recon.check-every=1h", "sprout.recon.recheck-after=200ms"})
@AutoConfigureMockMvc
class ReconApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final String KEY = "dev-only-service-key";
    static final String ASHA = UUID.randomUUID().toString();
    static final String ORDER = UUID.randomUUID().toString();

    static final AtomicReference<String> SESSION = new AtomicReference<>("2026-10-07");
    static final AtomicReference<String> TIME = new AtomicReference<>("13:00");
    static final Map<String, Long> LEDGER = new ConcurrentHashMap<>();
    static final AtomicReference<Long> BANK = new AtomicReference<>();
    static final AtomicInteger BANK_READS = new AtomicInteger();
    static final AtomicReference<Long> BANK_LATER = new AtomicReference<>();   // what the bank says from the second read on
    static final AtomicReference<String> HELD = new AtomicReference<>();
    static final AtomicReference<Long> DEMAT = new AtomicReference<>();
    static final AtomicReference<String> EXCHANGE_PRICE = new AtomicReference<>();
    static final AtomicReference<String> SETTLEMENT = new AtomicReference<>();
    static final AtomicReference<Boolean> DEPOSITORY_DOWN = new AtomicReference<>(false);
    static final HttpServer BOOKS = books();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + BOOKS.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=recon");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        for (String k : List.of("marketdata-url", "ledger-url", "oms-url", "settlement-url", "bank.url", "exchange.url", "depository.url")) {
            r.add("sprout.recon." + k, () -> base);
        }
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-07T07:30:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.RECON_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired Runs runs;

    @BeforeEach
    void everythingAgrees() {
        LEDGER.put("sprout:bank", 1_00_000_00L);
        BANK.set(1_00_000_00L);
        BANK_LATER.set(null);
        BANK_READS.set(0);
        HELD.set("250.00");
        DEMAT.set(6L);
        EXCHANGE_PRICE.set("1000.00");
        SETTLEMENT.set("COMPLETED");
        DEPOSITORY_DOWN.set(false);
    }

    JsonNode reconcile() throws Exception {
        return JSON.readTree(mvc.perform(post("/v1/runs").header("X-Service-Key", KEY)).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)
                .andReturn().getResponse().getContentAsString());
    }

    static JsonNode check(JsonNode run, String name) {
        for (JsonNode c : run.path("checks")) {
            if (c.path("check").asText().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no check " + name);
    }

    @Test
    void whenEverythingAgreesEveryCheckPasses() throws Exception {
        JsonNode run = reconcile();
        assertThat(run.path("status").asText()).as(run.toString()).isEqualTo("PASS");
        assertThat(run.path("checks").size()).isEqualTo(7);
        assertThat(check(run, "TRADES_MATCH_EXCHANGE").path("summary").asText()).contains("agree");
    }

    @Test
    void moneyThatDiffersOnBothLooksIsABreakButMoneyInFlightIsNot() throws Exception {
        BANK.set(1_00_500_00L);   // the bank has ₹500 the ledger doesn't
        JsonNode run = reconcile();
        assertThat(run.path("status").asText()).isEqualTo("FAIL");
        JsonNode bank = check(run, "BANK_MATCHES_LEDGER");
        assertThat(bank.path("status").asText()).isEqualTo("FAIL");
        assertThat(bank.path("differences").get(0).asText()).contains("100000.00").contains("100500.00");

        BANK_READS.set(0);
        BANK_LATER.set(1_00_000_00L);   // differs on the first look only: a deposit being booked
        assertThat(check(reconcile(), "BANK_MATCHES_LEDGER").path("status").asText()).isEqualTo("PASS");
    }

    @Test
    void customersWhoseMoneyOrSharesDifferAreListed() throws Exception {
        HELD.set("300.00");
        DEMAT.set(5L);
        JsonNode run = reconcile();
        assertThat(check(run, "HOLDS_MATCH_ORDERS").path("differences").get(0).asText()).contains(ASHA).contains("250.00").contains("300.00");
        assertThat(check(run, "DEMAT_MATCHES_HOLDINGS").path("differences").get(0).asText()).contains("depository 5, Sprout 6");
        assertThat(check(run, "UNSETTLED_MATCHES_ORDERS").path("status").asText()).isEqualTo("PASS");
    }

    @Test
    void anExecutionTheExchangeRemembersDifferentlyIsABreak() throws Exception {
        EXCHANGE_PRICE.set("1000.05");
        JsonNode trades = check(reconcile(), "TRADES_MATCH_EXCHANGE");
        assertThat(trades.path("status").asText()).isEqualTo("FAIL");
        assertThat(trades.path("differences").get(0).asText()).contains(ORDER).contains("1000.05");
    }

    @Test
    void aSettlementBreakOrALateSettlementFailsAndAnUnreadableBookIsAnError() throws Exception {
        SETTLEMENT.set("BREAK");
        assertThat(check(reconcile(), "SETTLEMENTS_HEALTHY").path("status").asText()).isEqualTo("FAIL");
        SETTLEMENT.set("COMPLETED");
        DEPOSITORY_DOWN.set(true);
        JsonNode run = reconcile();
        assertThat(run.path("status").asText()).as("nothing failed, but one check couldn't run").isEqualTo("ERROR");
        assertThat(check(run, "DEMAT_MATCHES_HOLDINGS").path("status").asText()).isEqualTo("ERROR");
    }

    @Test
    void theScheduledRunHappensOncePerSessionAfterItsTime() throws Exception {
        SESSION.set("2026-10-08");
        TIME.set("11:00");
        invokeScheduled();
        TIME.set("12:30");
        invokeScheduled();
        invokeScheduled();
        long scheduled = 0;
        for (JsonNode r : JSON.readTree(mvc.perform(get("/v1/runs").header("X-Service-Key", KEY)).andExpect(status().isOk())
                .andExpect(MATCHES_CONTRACT).andReturn().getResponse().getContentAsString()).path("runs")) {
            scheduled += r.path("trigger").asText().equals("SCHEDULED") && r.path("session").asText().equals("2026-10-08") ? 1 : 0;
        }
        assertThat(scheduled).isEqualTo(1);
        SESSION.set("2026-10-07");
        TIME.set("13:00");
        mvc.perform(post("/v1/runs")).andExpect(status().isUnauthorized()).andExpect(MATCHES_CONTRACT);
        mvc.perform(get("/v1/runs/" + UUID.randomUUID()).header("X-Service-Key", KEY)).andExpect(status().isNotFound());
    }

    void invokeScheduled() throws Exception {
        var m = Runs.class.getDeclaredMethod("scheduled");
        m.setAccessible(true);
        m.invoke(runs);
    }

    // ── the stand-ins ────────────────────────────────────────────────────────

    static HttpServer books() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/v1/market", ex -> reply(ex, 200, Map.of("state", "OPEN", "sessionDate", SESSION.get(),
                    "marketTime", SESSION.get() + "T" + TIME.get() + ":00+05:30")));
            s.createContext("/v1/trial-balance", ex -> reply(ex, 200, Map.of("assets", "100000.00", "liabilities", "100000.00", "balanced", true,
                    "accounts", 3)));
            s.createContext("/v1/accounts/", ex -> reply(ex, 200, Map.of("account", "sprout:bank", "kind", "ASSET",
                    "balance", rupees(LEDGER.get("sprout:bank")))));
            s.createContext("/v1/balances", ex -> {
                String pattern = ex.getRequestURI().getQuery();
                String kind = pattern.contains("order-hold") ? "order-hold" : "unsettled";
                reply(ex, 200, Map.of("balances", List.of(Map.of("account", "customer:" + ASHA + ":" + kind, "kind", "LIABILITY",
                        "balance", kind.equals("order-hold") ? "250.00" : "1200.00"))));
            });
            s.createContext("/partner/v1/account", ex -> {
                long balance = BANK_READS.getAndIncrement() > 0 && BANK_LATER.get() != null ? BANK_LATER.get() : BANK.get();
                reply(ex, 200, Map.of("vpa", "sprout@sproutbank", "holderName", "Sprout Investments", "balance", rupees(balance),
                        "openedAt", "2026-10-01T00:00:00Z"));
            });
            s.createContext("/internal/v1/recon", ex -> reply(ex, 200, Map.of("customers", List.of(Map.of("userId", ASHA, "held", HELD.get(),
                    "unsettled", "1200.00", "delivered", List.of(Map.of("symbol", "HARBOR", "quantity", 6)))))));
            s.createContext("/internal/v1/executions", ex -> reply(ex, 200, Map.of("executions", List.of(Map.of("orderId", ORDER, "userId", ASHA,
                    "tradeDate", "2026-10-06", "filledAt", "2026-10-06T05:00:00Z", "symbol", "HARBOR", "side", "BUY", "product", "CNC",
                    "quantity", 6, "price", "1000.00")))));
            s.createContext("/member/v1/trades", ex -> {
                List<Map<String, Object>> trades = new ArrayList<>();
                if (ex.getRequestURI().getQuery().contains("sessionDate=2026-10-06")) {
                    trades.add(Map.of("tradeId", UUID.randomUUID().toString(), "clientOrderId", ORDER, "clientCode", ASHA, "symbol", "HARBOR",
                            "side", "BUY", "quantity", 6, "price", EXCHANGE_PRICE.get(), "sessionDate", "2026-10-06", "executedAt", "2026-10-06T05:00:00Z"));
                }
                reply(ex, 200, Map.of("trades", trades));
            });
            s.createContext("/participant/v1/holdings", ex -> {
                if (DEPOSITORY_DOWN.get()) {
                    reply(ex, 503, Map.of());
                    return;
                }
                reply(ex, 200, Map.of("holdings", List.of(Map.of("boId", "1208160000000042", "clientRef", ASHA, "symbol", "HARBOR",
                        "quantity", DEMAT.get()))));
            });
            s.createContext("/v1/settlements", ex -> reply(ex, 200, Map.of("settlements", List.of(Map.of("id", "scc-1", "tradeDate", "2026-10-06",
                    "status", SETTLEMENT.get(), "fundsDirection", "PAY", "fundsAmount", "6000.00", "breakReason", "Funds differ",
                    "createdAt", "2026-10-07T04:00:00Z", "updatedAt", "2026-10-07T04:00:00Z")))));
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String rupees(long p) {
        return p / 100 + "." + String.format("%02d", p % 100);
    }

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
