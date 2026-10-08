package org.reasonstation.config;

import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DefaultUserInitializer {

    @Bean
    CommandLineRunner createLocalAdminUser(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${app.auth.default-user.enabled:true}") boolean enabled,
            @Value("${app.auth.default-user.alias:admin}") String alias,
            @Value("${app.auth.default-user.email:admin@localhost}") String email,
            @Value("${app.auth.default-user.password:admin-password}") String password) {
        return args -> {
            if (enabled && users.findByAlias(alias).isEmpty() && users.findByEmail(email).isEmpty()) {
                users.save(new User(alias, email, passwordEncoder.encode(password)));
            }
        };
    }
}
