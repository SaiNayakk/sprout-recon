package app.sprout.recon.domain;

import app.sprout.recon.config.ReconProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Every record reconciliation reads, each through the owning service's own API (never its
 * database): the ledger, Sprout Bank, the order service, the exchange, the depository and the
 * settlement back office. A record that can't be read is a {@link Unreadable}, and the check that
 * needed it is an ERROR, not a pass.
 */
@Component
public class Sources {

    static final Duration DEADLINE = Duration.ofSeconds(10);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static class Unreadable extends RuntimeException {
        public Unreadable(String what) {
            super(what);
        }
    }

    public record Market(LocalDate session, boolean open, LocalTime time) {}

    private final ReconProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Onward onward;

    public Sources(ReconProperties props, ObjectMapper json, Onward onward) {
        this.onward = onward;
        this.props = props;
        this.json = json;
    }

    public Market market() {
        JsonNode m = get("market data", props.marketdataUrl() + "/v1/market", null, null);
        return new Market(LocalDate.parse(m.path("sessionDate").asText()), "OPEN".equals(m.path("state").asText()),
                OffsetDateTime.parse(m.path("marketTime").asText()).atZoneSameInstant(IST).toLocalTime());
    }

    public JsonNode trialBalance() {
        return get("the ledger", props.ledgerUrl() + "/v1/trial-balance", null, null);
    }

    public long ledgerBalance(String account) {
        return Money.paise(get("the ledger", props.ledgerUrl() + "/v1/accounts/" + URLEncoder.encode(account, StandardCharsets.UTF_8), null, null)
                .path("balance").asText());
    }

    public JsonNode ledgerBalances(String pattern) {
        return get("the ledger", props.ledgerUrl() + "/v1/balances?pattern=" + URLEncoder.encode(pattern, StandardCharsets.UTF_8), null, null)
                .path("balances");
    }

    public long bankBalance() {
        return Money.paise(get("Sprout Bank", props.bank().url() + "/partner/v1/account", "X-Partner-Key", props.bank().partnerKey())
                .path("balance").asText());
    }

    public JsonNode omsRecon() {
        return get("the order service", props.omsUrl() + "/internal/v1/recon", "X-Service-Key", props.serviceKey()).path("customers");
    }

    public JsonNode executions(LocalDate from, LocalDate to) {
        return get("the order service", props.omsUrl() + "/internal/v1/executions?from=" + from + "&to=" + to, "X-Service-Key",
                props.serviceKey()).path("executions");
    }

    /** All of Sprout's trades on the exchange in a session, every page. */
    public List<JsonNode> exchangeTrades(LocalDate session) {
        List<JsonNode> all = new ArrayList<>();
        String after = null;
        while (true) {
            JsonNode page = get("the exchange", props.exchange().url() + "/member/v1/trades?limit=1000&sessionDate=" + session
                    + (after == null ? "" : "&after=" + after), "X-Member-Key", props.exchange().memberKey()).path("trades");
            page.forEach(all::add);
            if (page.size() < 1000) {
                return all;
            }
            after = page.get(page.size() - 1).path("tradeId").asText();
        }
    }

    public JsonNode dematHoldings() {
        return get("the depository", props.depository().url() + "/participant/v1/holdings", "X-Participant-Key", props.depository().participantKey())
                .path("holdings");
    }

    public JsonNode settlements() {
        return get("the settlement back office", props.settlementUrl() + "/v1/settlements", "X-Service-Key", props.serviceKey()).path("settlements");
    }

    private JsonNode get(String what, String url, String header, String value) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(DEADLINE).GET();
            onward.headers(req);
            if (header != null) {
                req.header(header, value);
            }
            HttpResponse<String> res = http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() != 200) {
                throw new Unreadable(what + " answered " + res.statusCode());
            }
            return json.readTree(res.body());
        } catch (Unreadable e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new Unreadable(what + " couldn't be read (" + e.getClass().getSimpleName() + ")");
        }
    }
}
