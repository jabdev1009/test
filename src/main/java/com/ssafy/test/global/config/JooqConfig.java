package com.ssafy.test.global.config;

import org.jooq.SQLDialect;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
import org.jooq.impl.DefaultConfiguration;
import org.springframework.boot.autoconfigure.jooq.DefaultConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JOOQ Configuration
 * PostgreSQL 대소문자 문제 해결
 */
@Configuration
public class JooqConfig {
    
    @Bean
    public DefaultConfigurationCustomizer jooqConfigurationCustomizer() {
        return (DefaultConfiguration configuration) -> {
            Settings settings = new Settings()
                .withRenderNameCase(RenderNameCase.LOWER);
//                .withRenderQuotedNames(org.jooq.conf.RenderQuotedNames.EXPLICIT_DEFAULT_UNQUOTED);
            configuration.set(settings);
            configuration.set(SQLDialect.POSTGRES);
        };
    }
}