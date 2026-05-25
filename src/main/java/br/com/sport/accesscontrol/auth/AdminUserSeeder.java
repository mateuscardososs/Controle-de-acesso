package br.com.sport.accesscontrol.auth;

import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import br.com.sport.accesscontrol.users.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Locale;

@Component
public class AdminUserSeeder implements ApplicationRunner {

    static final String DEFAULT_ADMIN_EMAIL = "admin@empresa.local";
    static final String DEFAULT_ADMIN_PASSWORD = "Admin@123456";

    private static final Logger log = LoggerFactory.getLogger(AdminUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public AdminUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder, Environment environment) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        validateProductionDoesNotUseDefaultAdminPassword();
        if (!seedEnabled()) {
            log.info("admin_seed_skipped enabled=false production_profile={}", productionProfileActive());
            return;
        }

        var email = adminEmail();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            log.info("admin_seed_skipped reason=user_exists email={}", email);
            return;
        }
        userRepository.save(new User(
                adminName(),
                email,
                passwordEncoder.encode(adminPassword()),
                UserRole.ADMIN,
                true
        ));
        log.warn("admin_seed_created email={} production_profile={}", email, productionProfileActive());
    }

    private boolean seedEnabled() {
        var configured = environment.getProperty("APP_SEED_ADMIN_ENABLED");
        if (configured != null && !configured.isBlank()) {
            return Boolean.parseBoolean(configured);
        }
        return !productionProfileActive();
    }

    private String adminEmail() {
        return property("APP_SEED_ADMIN_EMAIL", DEFAULT_ADMIN_EMAIL).toLowerCase(Locale.ROOT);
    }

    private String adminName() {
        return property("APP_SEED_ADMIN_NAME", "Administrador");
    }

    private String adminPassword() {
        var configured = environment.getProperty("APP_SEED_ADMIN_PASSWORD");
        if (productionProfileActive()) {
            if (configured == null || configured.isBlank()) {
                throw new IllegalStateException("APP_SEED_ADMIN_PASSWORD is required when admin seed is enabled in production.");
            }
            if (DEFAULT_ADMIN_PASSWORD.equals(configured)) {
                throw new IllegalStateException("The development admin password cannot be used in production.");
            }
            return configured;
        }
        return configured == null || configured.isBlank() ? DEFAULT_ADMIN_PASSWORD : configured;
    }

    private void validateProductionDoesNotUseDefaultAdminPassword() {
        if (!productionProfileActive()) {
            return;
        }
        userRepository.findByEmailIgnoreCase(DEFAULT_ADMIN_EMAIL).ifPresent(user -> {
            if (passwordEncoder.matches(DEFAULT_ADMIN_PASSWORD, user.getPasswordHash())) {
                log.error("critical_default_admin_credentials_detected email={}", DEFAULT_ADMIN_EMAIL);
                throw new IllegalStateException("Critical security violation: default admin credentials are present in production.");
            }
        });
    }

    private boolean productionProfileActive() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    }

    private String property(String name, String fallback) {
        var value = environment.getProperty(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
