package com.paytm.assignment.config;

import com.paytm.assignment.constant.UserRole;
import com.paytm.assignment.entity.UserEntity;
import com.paytm.assignment.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;

@Configuration
public class AuthConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CommandLineRunner bootstrapAdmin(UserRepository users, PasswordEncoder passwordEncoder,
                                     @Value("${app.bootstrap-admin.identifier:admin}") String identifier,
                                     @Value("${app.bootstrap-admin.password:admin123}") String password) {
        return args -> {
            String normalizedIdentifier = identifier.trim().toLowerCase(Locale.ROOT);
            if (normalizedIdentifier.isBlank() || password.length() < 8) {
                throw new IllegalStateException("Bootstrap admin identifier and an 8+ character password are required");
            }
            if (!users.existsByEmailIgnoreCase(normalizedIdentifier)) {
                users.save(new UserEntity(normalizedIdentifier, passwordEncoder.encode(password), UserRole.ADMIN));
            }
        };
    }
}
