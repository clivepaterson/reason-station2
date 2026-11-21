package org.reasonstation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionJdbcConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProductionJdbcConfig.class);

    @Test
    void providesPostgresDialectWithoutOpeningDatabaseForProductionAot() {
        contextRunner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasSingleBean(JdbcDialect.class).doesNotHaveBean(DataSource.class);
            assertThat(context.getBean(JdbcDialect.class)).isSameAs(JdbcPostgresDialect.INSTANCE);
        });
    }

    @Test
    void leavesLocalDialectDetectionUnchanged() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(JdbcDialect.class));
    }
}
