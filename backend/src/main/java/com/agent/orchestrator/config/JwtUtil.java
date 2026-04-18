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

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    private static final String DEFAULT_SECRET = "axolotl-secret-key-must-be-at-least-32-chars-long-for-hs256";

    @Value("${axolotl.jwt.secret}")
    private String secret;

    @Value("${axolotl.jwt.expiration:86400000}")
    private long expirationMs; // 24h default

    @jakarta.annotation.PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            SecureRandom random = new SecureRandom();
            byte[] bytes = new byte[64];
            random.nextBytes(bytes);
            secret = Base64.getEncoder().encodeToString(bytes);
            log.warn("JWT secret is not set — generated random secret. Tokens will not survive restart. Set axolotl.jwt.secret in properties.");
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
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
