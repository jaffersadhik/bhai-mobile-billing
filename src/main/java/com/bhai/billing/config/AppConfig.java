package com.bhai.billing.config;

import com.bhai.billing.core.BillingService;
import com.bhai.billing.core.JdbcDb;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public BillingService billingService(DataSource dataSource, @Value("${bmb.zone:Asia/Kolkata}") String zone) {
        return new BillingService(new JdbcDb(dataSource), ZoneId.of(zone));
    }

    /** Runs after schema.sql has created the tables: creates admin / admin123 on an empty database. */
    @Bean
    public ApplicationRunner createDefaultAdmin(BillingService service) {
        return args -> {
            if (service.ensureDefaultAdmin())
                log.warn("Bhai Mobile Billing: first start - created login admin / admin123. Change this password immediately after login.");
        };
    }
}
