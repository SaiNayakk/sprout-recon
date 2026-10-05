package app.sprout.recon.domain;

import app.sprout.recon.config.ReconProperties;
import app.sprout.recon.domain.Checks.Result;
import app.sprout.recon.domain.Checks.Status;
import app.sprout.recon.domain.Sources.Market;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Runs reconciliation: once a session, after {@code run-at} market time (by then the previous day has
 * settled), and whenever asked. Every run and every check's findings are kept.
 */
@Service
public class Runs {

    private static final Logger log = LoggerFactory.getLogger(Runs.class);

    public record Run(UUID id, String trigger, LocalDate session, String status, Instant startedAt, Instant finishedAt, List<Result> checks) {}

    private final JdbcClient db;
    private final Clock clock;
    private final Sources sources;
    private final Checks checks;
    private final ReconProperties props;
    private final ObjectMapper json;

    public Runs(JdbcClient db, Clock clock, Sources sources, Checks checks, ReconProperties props, ObjectMapper json) {
        this.db = db;
        this.clock = clock;
        this.sources = sources;
        this.checks = checks;
        this.props = props;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${sprout.recon.check-every:60s}")
    void scheduled() {
        try {
            Market m = sources.market();
            if (m.open() && !m.time().isBefore(props.runAt()) && byTriggerAndSession("SCHEDULED", m.session()).isEmpty()) {
                run("SCHEDULED", m);
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't see whether reconciliation is due: {}", e.getMessage());
        }
    }

    public Run onDemand() {
        Market m;
        try {
            m = sources.market();
        } catch (Sources.Unreadable e) {
            // without market data, check against today in India; the checks that need more report ERROR themselves
            m = new Market(LocalDate.now(clock.withZone(Sources.IST)), false, java.time.LocalTime.now(clock.withZone(Sources.IST)));
        }
        return run("ON_DEMAND", m);
    }

    private Run run(String trigger, Market m) {
        UUID id = UUID.randomUUID();
        LocalDate session = m.session();
        try {
            db.sql("INSERT INTO runs (id, trigger, session, started_at) VALUES (?, ?, ?, ?)")
                    .params(id, trigger, session, Timestamp.from(clock.instant())).update();
        } catch (DuplicateKeyException e) {
            return byTriggerAndSession(trigger, session).orElseThrow(() -> e);   // another instance just started today's
        }
        List<Result> results = checks.all(m);
        for (Result r : results) {
            db.sql("INSERT INTO checks (run_id, name, status, summary, differences) VALUES (?, ?, ?, ?, ?)")
                    .params(id, r.check(), r.status().name(), r.summary(), write(r.differences())).update();
        }
        Status status = results.stream().anyMatch(r -> r.status() == Status.FAIL) ? Status.FAIL
                : results.stream().anyMatch(r -> r.status() == Status.ERROR) ? Status.ERROR : Status.PASS;
        db.sql("UPDATE runs SET status = ?, finished_at = ? WHERE id = ?").params(status.name(), Timestamp.from(clock.instant()), id).update();
        if (status == Status.PASS) {
            log.info("Reconciliation {} ({}): everything agrees", id, trigger);
        } else {
            results.stream().filter(r -> r.status() != Status.PASS)
                    .forEach(r -> log.error("RECONCILIATION {} {}: {} {}", r.status(), r.check(), r.summary(), r.differences()));
        }
        return run(id).orElseThrow();
    }

    public List<Run> recent() {
        return db.sql("SELECT id FROM runs ORDER BY started_at DESC LIMIT 30").query(UUID.class).list().stream()
                .map(id -> run(id).orElseThrow()).toList();
    }

    public Optional<Run> run(UUID id) {
        return db.sql("SELECT id, trigger, session, status, started_at, finished_at FROM runs WHERE id = ?").param(id)
                .query((rs, n) -> new Run(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), results(id)))
                .optional();
    }

    private Optional<Run> byTriggerAndSession(String trigger, LocalDate session) {
        return db.sql("SELECT id FROM runs WHERE trigger = ? AND session = ? ORDER BY started_at DESC LIMIT 1").params(trigger, session)
                .query(UUID.class).optional().flatMap(this::run);
    }

    private List<Result> results(UUID id) {
        return db.sql("SELECT name, status, summary, differences FROM checks WHERE run_id = ? ORDER BY name").param(id)
                .query((rs, n) -> new Result(rs.getString(1), Status.valueOf(rs.getString(2)), rs.getString(3), read(rs.getString(4)))).list();
    }

    private String write(List<String> differences) {
        try {
            return json.writeValueAsString(differences);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> read(String differences) {
        try {
            return json.readValue(differences, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
