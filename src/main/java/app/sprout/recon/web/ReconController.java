package app.sprout.recon.web;

import app.sprout.recon.config.ReconProperties;
import app.sprout.recon.domain.ApiException;
import app.sprout.recon.domain.ErrorCode;
import app.sprout.recon.domain.Runs;
import app.sprout.recon.domain.Runs.Run;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Reconciliation for operators and services (recon-v1.yaml). */
@RestController
public class ReconController {

    private final Runs runs;
    private final ReconProperties props;

    public ReconController(Runs runs, ReconProperties props) {
        this.runs = runs;
        this.props = props;
    }

    @PostMapping("/v1/runs")
    public ResponseEntity<Map<String, Object>> start(@RequestHeader(value = "X-Service-Key", required = false) String key) {
        requireKey(key);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(runs.onDemand()));
    }

    @GetMapping("/v1/runs")
    public Map<String, Object> list(@RequestHeader(value = "X-Service-Key", required = false) String key) {
        requireKey(key);
        return Map.of("runs", runs.recent().stream().map(ReconController::dto).toList());
    }

    @GetMapping("/v1/runs/{id}")
    public Map<String, Object> get(@RequestHeader(value = "X-Service-Key", required = false) String key, @PathVariable UUID id) {
        requireKey(key);
        return dto(runs.run(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No reconciliation run " + id + ".")));
    }

    private void requireKey(String key) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), props.serviceKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Operators and Sprout services only.");
        }
    }

    static Map<String, Object> dto(Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id().toString());
        m.put("trigger", r.trigger());
        if (r.session() != null) {
            m.put("session", r.session().toString());
        }
        m.put("status", r.status());
        m.put("startedAt", r.startedAt().toString());
        if (r.finishedAt() != null) {
            m.put("finishedAt", r.finishedAt().toString());
        }
        m.put("checks", r.checks().stream().map(c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("check", c.check());
            out.put("status", c.status().name());
            out.put("summary", c.summary());
            if (!c.differences().isEmpty()) {
                out.put("differences", c.differences());
            }
            return out;
        }).toList());
        return m;
    }
}
