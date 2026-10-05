package app.sprout.recon.domain;

import app.sprout.recon.config.ReconProperties;
import app.sprout.recon.domain.Sources.Market;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The checks. Each compares two records that should agree, keyed (by customer, by order, by share),
 * and lists the differences. Money moves while a check reads, so the money checks compare twice, a
 * moment apart, and report only what differed both times: a payment in flight is not a break.
 */
@Component
public class Checks {

    public enum Status { PASS, FAIL, ERROR }

    public record Result(String check, Status status, String summary, List<String> differences) {}

    static final int MAX_LISTED = 20;

    private final Sources sources;
    private final ReconProperties props;

    public Checks(Sources sources, ReconProperties props) {
        this.sources = sources;
        this.props = props;
    }

    public List<Result> all(Market market) {
        List<Result> out = new ArrayList<>();
        out.add(run("LEDGER_BALANCED", this::ledgerBalanced));
        out.add(run("BANK_MATCHES_LEDGER", () -> settled("Sprout's money at the bank", () -> {
            Map<String, String> d = new TreeMap<>();
            long ledger = sources.ledgerBalance("sprout:bank");
            long bank = sources.bankBalance();
            if (ledger != bank) {
                d.put("sprout:bank", "ledger ₹" + Money.rupees(ledger) + ", Sprout Bank ₹" + Money.rupees(bank));
            }
            return d;
        })));
        out.add(run("HOLDS_MATCH_ORDERS", () -> settled("customers' held money", () -> customerMoney("order-hold", "held"))));
        out.add(run("UNSETTLED_MATCHES_ORDERS", () -> settled("customers' unsettled money", () -> customerMoney("unsettled", "unsettled"))));
        out.add(run("TRADES_MATCH_EXCHANGE", () -> settled("executions and exchange trades since " + market.session().minusDays(props.tradeDays()),
                () -> trades(market.session()))));
        out.add(run("DEMAT_MATCHES_HOLDINGS", () -> settled("customers' delivered shares", this::demat)));
        out.add(run("SETTLEMENTS_HEALTHY", () -> settlements(market.session())));
        return out;
    }

    private Result run(String name, Supplier<Result> check) {
        try {
            Result r = check.get();
            return new Result(name, r.status(), r.summary(), r.differences());
        } catch (Sources.Unreadable e) {
            return new Result(name, Status.ERROR, "Couldn't check: " + e.getMessage() + ".", List.of());
        }
    }

    private Result ledgerBalanced() {
        JsonNode t = sources.trialBalance();
        if (t.path("balanced").asBoolean()) {
            return pass("The ledger balances: assets ₹" + t.path("assets").asText() + " = liabilities ₹" + t.path("liabilities").asText() + ".");
        }
        return new Result("", Status.FAIL, "The ledger doesn't balance.",
                List.of("assets ₹" + t.path("assets").asText() + ", liabilities ₹" + t.path("liabilities").asText()));
    }

    /** Compares twice, {@code recheckAfter} apart; only keys that differ both times (the same way) are breaks. */
    private Result settled(String what, Supplier<Map<String, String>> compare) {
        Map<String, String> first = compare.get();
        if (first.isEmpty()) {
            return pass("All " + what + " agree.");
        }
        try {
            Thread.sleep(props.recheckAfter().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<String, String> second = compare.get();
        List<String> both = new ArrayList<>();
        for (var d : second.entrySet()) {
            if (first.containsKey(d.getKey())) {
                both.add(d.getKey() + ": " + d.getValue());
            }
        }
        if (both.isEmpty()) {
            return pass("All " + what + " agree (a difference on the first look had resolved itself: money in flight).");
        }
        return fail(both.size() + " difference(s) in " + what + ".", both);
    }

    /** Each customer's money in one ledger account kind against what the order service expects. */
    private Map<String, String> customerMoney(String kind, String field) {
        Map<String, Long> ledger = new TreeMap<>();
        for (JsonNode b : sources.ledgerBalances("customer:*:" + kind)) {
            String user = b.path("account").asText().split(":")[1];
            ledger.put(user, Money.paise(b.path("balance").asText()));
        }
        Map<String, Long> books = new TreeMap<>();
        for (JsonNode c : sources.omsRecon()) {
            books.put(c.path("userId").asText(), Money.paise(c.path(field).asText()));
        }
        Map<String, String> d = new TreeMap<>();
        for (String user : union(ledger.keySet(), books.keySet())) {
            long l = ledger.getOrDefault(user, 0L);
            long b = books.getOrDefault(user, 0L);
            if (l != b) {
                d.put(user, "ledger ₹" + Money.rupees(l) + ", orders say ₹" + Money.rupees(b));
            }
        }
        return d;
    }

    /** Every execution the order service booked is a trade the exchange made, the same, and the exchange made no others. */
    private Map<String, String> trades(LocalDate session) {
        LocalDate from = session.minusDays(props.tradeDays());
        Map<String, String> ours = new TreeMap<>();
        for (JsonNode e : sources.executions(from, session)) {
            ours.put(e.path("orderId").asText(), e.path("symbol").asText() + " " + e.path("side").asText() + " " + e.path("quantity").asLong()
                    + " at " + e.path("price").asText() + " on " + e.path("tradeDate").asText());
        }
        Map<String, String> theirs = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(session); d = d.plusDays(1)) {
            for (JsonNode t : sources.exchangeTrades(d)) {
                theirs.put(t.path("clientOrderId").asText(), t.path("symbol").asText() + " " + t.path("side").asText() + " "
                        + t.path("quantity").asLong() + " at " + t.path("price").asText() + " on " + t.path("sessionDate").asText());
            }
        }
        Map<String, String> d = new TreeMap<>();
        for (String id : union(ours.keySet(), theirs.keySet())) {
            if (!Objects.equals(ours.get(id), theirs.get(id))) {
                d.put("order " + id, "Sprout " + ours.getOrDefault(id, "has nothing") + "; the exchange " + theirs.getOrDefault(id, "has nothing"));
            }
        }
        return d;
    }

    /** What the depository holds for each customer, against their delivered shares at Sprout. */
    private Map<String, String> demat() {
        Map<String, Long> depository = new TreeMap<>();
        for (JsonNode h : sources.dematHoldings()) {
            depository.put(h.path("clientRef").asText() + " " + h.path("symbol").asText(), h.path("quantity").asLong());
        }
        Map<String, Long> books = new TreeMap<>();
        for (JsonNode c : sources.omsRecon()) {
            for (JsonNode h : c.path("delivered")) {
                books.put(c.path("userId").asText() + " " + h.path("symbol").asText(), h.path("quantity").asLong());
            }
        }
        Map<String, String> d = new TreeMap<>();
        for (String key : union(depository.keySet(), books.keySet())) {
            long dep = depository.getOrDefault(key, 0L);
            long ours = books.getOrDefault(key, 0L);
            if (dep != ours) {
                d.put(key, "depository " + dep + ", Sprout " + ours);
            }
        }
        return d;
    }

    /** No break, and nothing still unsettled days after its trade date (allowing for a weekend). */
    private Result settlements(LocalDate session) {
        List<String> d = new ArrayList<>();
        int done = 0;
        for (JsonNode s : sources.settlements()) {
            String status = s.path("status").asText();
            LocalDate day = LocalDate.parse(s.path("tradeDate").asText());
            if (status.equals("BREAK")) {
                d.add(day + ": a break (" + s.path("breakReason").asText() + ")");
            } else if (!status.equals("COMPLETED") && day.isBefore(session.minusDays(4))) {
                d.add(day + ": still " + status);
            } else if (status.equals("COMPLETED")) {
                done++;
            }
        }
        return d.isEmpty() ? pass(done + " recent settlement(s) completed; none broken or late.") : fail(d.size() + " settlement problem(s).", d);
    }

    private static TreeSet<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        TreeSet<String> all = new TreeSet<>(a);
        all.addAll(b);
        return all;
    }

    private static Result pass(String summary) {
        return new Result("", Status.PASS, summary, List.of());
    }

    private static Result fail(String summary, List<String> differences) {
        return new Result("", Status.FAIL, summary, differences.size() > MAX_LISTED ? differences.subList(0, MAX_LISTED) : differences);
    }
}
