package com.agent.orchestrator.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        // Set fields via reflection since @Value won't work in unit test
        setField(jwtUtil, "secret", "test-secret-key-must-be-at-least-32-characters-long-for-hs256");
        setField(jwtUtil, "expirationMs", 86400000L);
    }

    @Test
    void generateAndParseToken() {
        String token = jwtUtil.generateToken("admin", "admin");

        assertEquals("admin", jwtUtil.getUsername(token));
        assertEquals("admin", jwtUtil.getRole(token));
    }

    @Test
    void isValid_validToken() {
        String token = jwtUtil.generateToken("user", "user");
        assertTrue(jwtUtil.isValid(token));
    }

    @Test
    void isValid_invalidToken() {
        assertFalse(jwtUtil.isValid("invalid.token.here"));
    }

    @Test
    void isValid_emptyToken() {
        assertFalse(jwtUtil.isValid(""));
    }

    @Test
    void getUsername_correctValue() {
        String token = jwtUtil.generateToken("testuser", "viewer");
        assertEquals("testuser", jwtUtil.getUsername(token));
    }

    @Test
    void getRole_correctValue() {
        String token = jwtUtil.generateToken("admin", "admin");
        assertEquals("admin", jwtUtil.getRole(token));
    }

    @Test
    void getRole_viewerRole() {
        String token = jwtUtil.generateToken("viewer", "viewer");
        assertEquals("viewer", jwtUtil.getRole(token));
    }

    private static void setField(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
