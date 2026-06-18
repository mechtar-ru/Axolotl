package com.agent.orchestrator.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    @Value("${axolotl.jwt.secret:}")
    private String secret;

    @Value("${axolotl.jwt.expiration:86400000}")
    private long expirationMs; // 24h default

    @jakarta.annotation.PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            log.error("JWT_SECRET not configured! Using random fallback — all tokens invalid after restart.");
            log.error("Set axolotl.jwt.secret in application.yml or JWT_SECRET env var for production use.");
            SecureRandom random = new SecureRandom();
            byte[] bytes = new byte[64];
            random.nextBytes(bytes);
            secret = Base64.getEncoder().encodeToString(bytes);
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("jti", UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getUsername(String token) {
        return parseToken(token).getSubject();
    }

    public String getRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
