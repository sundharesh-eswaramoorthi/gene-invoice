package com.geneinvoice.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs
    ) {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secret);
            if (keyBytes.length < 32) {
                keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    public static final String CREDENTIALS_CHANGED_AT = "cga";

    public String generateToken(String username, Map<String, Object> claims) {
        Date now = new Date();
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public Map<String, Object> claimsFor(com.geneinvoice.user.User user) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("role", user.getRole() == null ? null : user.getRole().getName());
        if (user.getCredentialsChangedAt() != null) {
            claims.put(CREDENTIALS_CHANGED_AT, user.getCredentialsChangedAt().toEpochMilli());
        }
        return claims;
    }

    public static boolean isCurrent(Claims claims, Instant credentialsChangedAt) {
        if (credentialsChangedAt == null) return true;
        Object minted = claims.get(CREDENTIALS_CHANGED_AT);
        return minted instanceof Number at && at.longValue() >= credentialsChangedAt.toEpochMilli();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
