package br.com.sport.accesscontrol.auth;

import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import br.com.sport.accesscontrol.users.UserRole;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminUserSeeder implements ApplicationRunner {

    static final String ADMIN_EMAIL = "admin@sport.local";
    static final String ADMIN_PASSWORD = "Admin@123456";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmailIgnoreCase(ADMIN_EMAIL)) {
            return;
        }
        userRepository.save(new User(
                "Administrador Sport",
                ADMIN_EMAIL,
                passwordEncoder.encode(ADMIN_PASSWORD),
                UserRole.ADMIN,
                true
        ));
    }
}
