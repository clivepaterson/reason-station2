package org.reasonstation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionJdbcConfig {
    @Bean
    JdbcDialect jdbcDialect() {
        // AOT repository generation must not connect to the production database.
        return JdbcPostgresDialect.INSTANCE;
    }
}
