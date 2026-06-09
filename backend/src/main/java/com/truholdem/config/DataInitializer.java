package com.truholdem.config;

import com.truholdem.dto.UserRegistrationDto;
import com.truholdem.model.Role;
import com.truholdem.model.User;
import com.truholdem.repository.RoleRepository;
import com.truholdem.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initializes required data on application startup: ensures the default roles exist and, when explicitly
 * enabled, seeds a single ADMIN user.
 *
 * <p>The admin seed is gated by {@code app.seed-admin.enabled} (default {@code false}) and is intended for
 * dev / CI only (e.g. the k6 tournament smoke needs an admin to create + start tournaments). It MUST stay off
 * in production — leaving it enabled there would ship a known-credentials admin account.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final RoleRepository roleRepository;
    private final UserService userService;

    @Value("${app.seed-admin.enabled:false}")
    private boolean seedAdminEnabled;

    @Value("${app.seed-admin.username:}")
    private String seedAdminUsername;

    @Value("${app.seed-admin.password:}")
    private String seedAdminPassword;

    @Value("${app.seed-admin.email:}")
    private String seedAdminEmail;

    public DataInitializer(RoleRepository roleRepository, UserService userService) {
        this.roleRepository = roleRepository;
        this.userService = userService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        initializeRoles();
        seedAdminIfEnabled();
    }

    private void initializeRoles() {
        createRoleIfNotExists("USER", "Standard user role");
        createRoleIfNotExists("ADMIN", "Administrator role");
        logger.info("Role initialization completed");
    }

    private void createRoleIfNotExists(String name, String description) {
        if (!roleRepository.existsByName(name)) {
            Role role = new Role(name, description);
            roleRepository.save(role);
            logger.info("Created role: {}", name);
        } else {
            logger.debug("Role already exists: {}", name);
        }
    }

    /**
     * Dev/CI only: create one ADMIN user from configured credentials if it does not already exist.
     * No-op unless {@code app.seed-admin.enabled=true}. Never enable in production.
     */
    private void seedAdminIfEnabled() {
        if (!seedAdminEnabled) {
            return;
        }
        if (seedAdminUsername == null || seedAdminUsername.isBlank()
                || seedAdminPassword == null || seedAdminPassword.isBlank()) {
            logger.warn("app.seed-admin.enabled=true but username/password are not set — skipping admin seed");
            return;
        }
        if (userService.findByUsername(seedAdminUsername).isPresent()) {
            logger.info("Seed admin '{}' already exists — skipping", seedAdminUsername);
            return;
        }

        UserRegistrationDto dto = new UserRegistrationDto();
        dto.setUsername(seedAdminUsername);
        dto.setEmail(seedAdminEmail != null && !seedAdminEmail.isBlank()
                ? seedAdminEmail : seedAdminUsername + "@local");
        dto.setPassword(seedAdminPassword);

        User admin = userService.createUser(dto);
        userService.addRoleToUser(admin.getId(), "ADMIN");
        logger.warn("Seeded ADMIN user '{}' (app.seed-admin.enabled=true — DEV/CI ONLY, must stay off in prod)",
                seedAdminUsername);
    }
}
