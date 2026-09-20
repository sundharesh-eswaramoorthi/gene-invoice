package com.geneinvoice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Keeps a request's EntityManager open until the response is written, as Spring Boot does by
 * default ({@code spring.jpa.open-in-view}, turned off in application.yml), for every endpoint except
 * email's. Most controllers map lazy associations after their service returns and rely on that.
 * <p>
 * Email, the inbox and the Gmail connections are left out because a single send, a retry, "sync now",
 * connecting Gmail and disconnecting someone's Gmail call the mail service within the request —
 * connecting waits on Google, up to tens of seconds. An open EntityManager keeps the connection of
 * its first query until the request ends, so each of those would hold one of the pool's few
 * connections for the whole call, and a slow service would starve the rest of the app. The mail service's webhook is left out too: it applies
 * each event in a transaction of its own, which must not share one persistence context. Their
 * services therefore do all their database work, lazy loading included, inside transactions of their own.
 */
@Configuration
public class OpenEntityManagerInViewConfig implements WebMvcConfigurer {

    static final String[] WITHOUT_OPEN_ENTITY_MANAGER = {"/api/emails", "/api/emails/**", "/api/inbox", "/api/inbox/**",
            "/api/me/gmail", "/api/users/*/gmail", "/api/mail-service/**"};

    @Bean
    public OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor() {
        return new OpenEntityManagerInViewInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addWebRequestInterceptor(openEntityManagerInViewInterceptor())
                .excludePathPatterns(WITHOUT_OPEN_ENTITY_MANAGER);
    }
}
