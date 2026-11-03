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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class AppConfig {
    private BillingService service;

    /** software invoices: every month on the 2nd at 8 AM (India time) for the month that just ended */
    @Scheduled(cron = "0 0 8 2 * *", zone = "${bmb.zone:Asia/Kolkata}")
    public void monthlyInvoices() {
        if (service == null) return;
        try {
            int n = service.generateDueInvoices();
            log.info("MBill (Mobile Bill): monthly invoices generated: {}", n);
        } catch (RuntimeException e) {
            log.error("Monthly invoice generation failed", e);
        }
    }
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public BillingService billingService(DataSource dataSource, @Value("${bmb.zone:Asia/Kolkata}") String zone) {
        return new BillingService(new JdbcDb(dataSource), ZoneId.of(zone));
    }

    /** Runs after schema.sql has created the tables: creates admin / admin1234 on an empty database. */
    @Bean
    public ApplicationRunner createDefaultAdmin(BillingService service) {
        return args -> {
            service.migrate();
            this.service = service;
            try {
                int n = service.generateDueInvoices();          // catch up if the server was down on the 2nd
                if (n > 0) log.info("MBill (Mobile Bill): {} pending monthly invoices generated at start-up", n);
            } catch (RuntimeException e) {
                log.error("Invoice catch-up failed", e);
            }
            if (service.ensureDefaultAdmin())
                log.warn("MBill (Mobile Bill): first start - created login admin / admin1234. Change this password immediately after login.");
        };
    }
}
