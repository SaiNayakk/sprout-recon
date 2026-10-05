package app.sprout.recon.config;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.recon} in recon.yml. */
@ConfigurationProperties("sprout.recon")
public record ReconProperties(
        String serviceKey,
        Duration checkEvery,
        LocalTime runAt,
        Duration recheckAfter,
        int tradeDays,
        String marketdataUrl,
        String ledgerUrl,
        String omsUrl,
        String settlementUrl,
        Keyed bank,
        Keyed exchange,
        Keyed depository) {

    /** A service called with its own key (bank partner key, exchange member key, depository participant key). */
    public record Keyed(String url, String partnerKey, String memberKey, String participantKey) {}
}
