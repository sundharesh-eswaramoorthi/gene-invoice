package com.geneinvoice.config;

import com.geneinvoice.common.asof.AsOfInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class OpenEntityManagerInViewConfig implements WebMvcConfigurer {

    // Registered here rather than in a configuration of its own because this is already the one
    // place the application's interceptor chain is declared, and the as-of guard has to see every
    // mapping — including the POST exports, which is where the allowlist earns its keep (B3).
    private final AsOfInterceptor asOfInterceptor;

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
        // No excludePathPatterns: refusal is the default, so every mapping in the application has
        // to be asked, including the ones that hold no entity manager open (B3).
        registry.addInterceptor(asOfInterceptor);
    }
}
