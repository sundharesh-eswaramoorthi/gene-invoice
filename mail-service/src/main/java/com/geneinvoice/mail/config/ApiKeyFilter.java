package com.geneinvoice.mail.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    static final String MESSAGE = "Missing or invalid API key";

    private final byte[] expected;
    private final ObjectMapper json;

    public ApiKeyFilter(String apiKey, ObjectMapper json) {
        this.expected = sha256(apiKey.trim());
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(sha256(given.trim()), expected)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            json.writeValue(response.getOutputStream(), ApiErrors.Body.of(HttpStatus.UNAUTHORIZED, MESSAGE));
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @Configuration
    static class Registration {

        @Bean
        FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(MailProperties properties, ObjectMapper json) {
            FilterRegistrationBean<ApiKeyFilter> registration =
                    new FilterRegistrationBean<>(new ApiKeyFilter(properties.getApiKey(), json));
            registration.addUrlPatterns("/api/v1/*");
            registration.setName("apiKeyFilter");
            return registration;
        }
    }
}
