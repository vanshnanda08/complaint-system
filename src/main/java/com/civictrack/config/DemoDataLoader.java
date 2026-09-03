package com.civictrack.config;

import com.civictrack.user.Role;
import com.civictrack.user.User;
import com.civictrack.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Seeds one account per role so the login screen has something to demo against. */
@Component
public class DemoDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DemoDataLoader(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        seed("citizen@civictrack.in", "Ravi Sharma", Role.CITIZEN);
        seed("staff@civictrack.in", "Amrit Kaur", Role.STAFF);
        seed("supervisor@civictrack.in", "Harpreet Singh", Role.SUPERVISOR);
        seed("admin@civictrack.in", "Neha Gupta", Role.ADMIN);
    }

    private void seed(String email, String fullName, Role role) {
        if (users.existsByEmailIgnoreCase(email)) {
            return;
        }
        users.save(new User(email, null, passwordEncoder.encode("password123"), fullName, role));
        log.info("Seeded demo {} account: {} / password123", role, email);
    }
}
