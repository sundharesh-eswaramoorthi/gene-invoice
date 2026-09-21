package com.geneinvoice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
