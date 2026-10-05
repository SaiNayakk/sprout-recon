package app.sprout.recon.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReconBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
