package com.civictrack.user.auth;

import com.civictrack.user.AppUser;
import com.civictrack.user.Role;
import com.civictrack.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gives the seeded org-chart accounts a password, for the demo only.
 *
 * <p>V4 seeds those accounts with a null hash so that a migration -- committed
 * to version control, identical everywhere, applied to production
 * automatically -- never carries credentials. But the demo needs to log in as a
 * supervisor on stage, so somebody has to set them.
 *
 * <p>This is that somebody, and it is deliberately narrow: it runs under the
 * {@code demo} profile only, it touches only accounts that still have no hash,
 * and it never touches a citizen account. It cannot promote anybody either --
 * the roles come from the migration, not from here.
 */
/**
 * Gives the seeded staff accounts a password so somebody can actually log in.
 *
 * <p>Runs under {@code demo} and {@code deploy}. Those two profiles differ in
 * their timings, not in this: {@code demo} compresses SLAs so a breach happens
 * while a slide is on screen, {@code deploy} keeps production deadlines. Both
 * need the accounts to be usable, and neither should have to activate the
 * other to get it.
 *
 * <p>It does nothing at all unless {@code civictrack.demo.staff-password} is
 * set, and it logs a warning saying so. That is the safe default: a profile
 * activated by accident cannot create a login.
 */
@Component
@Profile({"demo", "deploy"})
@RequiredArgsConstructor
@Slf4j
public class DemoAccountBootstrap implements ApplicationRunner {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Value("${civictrack.demo.staff-password:}")
    private String demoPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (demoPassword == null || demoPassword.isBlank()) {
            log.warn("Demo profile is active but civictrack.demo.staff-password is unset; "
                     + "the seeded staff accounts remain unable to log in");
            return;
        }

        List<AppUser> withoutPassword = users.findAll().stream()
                .filter(u -> u.getPasswordHash() == null)
                .filter(u -> u.getRole() != Role.CITIZEN)
                .toList();

        String hash = passwordEncoder.encode(demoPassword);
        withoutPassword.forEach(u -> u.setPasswordHash(hash));
        users.saveAll(withoutPassword);

        log.info("Demo profile: enabled login for {} seeded staff accounts", withoutPassword.size());
    }
}
