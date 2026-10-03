package com.paytm.assignment.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditActorProvider")
public class JpaAuditingConfiguration {

    @Bean
    public AuditorAware<String> auditActorProvider() {
        return () -> Optional.ofNullable(MDC.get("actorId")).or(() -> Optional.of("SYSTEM"));
    }
}
