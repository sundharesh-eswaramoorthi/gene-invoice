package com.geneinvoice.common.scope;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

@Configuration
public class ViewScopeConfig implements WebMvcConfigurer {

    private final Clock clock;
    private final AsOfSupport asOfSupport;

    public ViewScopeConfig(Clock clock, AsOfSupport asOfSupport) {
        this.clock = clock;
        this.asOfSupport = asOfSupport;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ViewScopeInterceptor(clock, asOfSupport)).addPathPatterns("/api/**");
    }
}
