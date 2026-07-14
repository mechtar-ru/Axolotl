package com.agent.orchestrator.controller;

import com.agent.orchestrator.config.JwtUtil;
import com.agent.orchestrator.model.AppUser;
import com.agent.orchestrator.model.RefreshToken;
import com.agent.orchestrator.repository.RefreshTokenRepository;
import com.agent.orchestrator.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private volatile boolean adminInitialized = false;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        ensureDefaultAdmin();
    }

    private synchronized void ensureDefaultAdmin() {
        if (adminInitialized) return;
        // DEV ONLY: hardcoded credentials for local development
        // In production, use a proper auth provider
        if (userRepository.findByUsername("admin") == null) {
            AppUser admin = new AppUser(
                    UUID.randomUUID().toString(),
                    "admin",
                    passwordEncoder.encode("admin"),
                    "admin"
            );
            userRepository.save(admin);
            log.warn("Default admin user created (admin:admin) — DEV ONLY, insecure for production");
        }
        if (userRepository.findByUsername("tech") == null) {
            // DEV ONLY: hardcoded credentials for local development
            AppUser tech = new AppUser(
                    UUID.randomUUID().toString(),
                    "tech",
                    passwordEncoder.encode("tech"),
                    "tech"
            );
            userRepository.save(tech);
            log.warn("Tech user created (tech:tech) — DEV ONLY, insecure for production");
        }
        adminInitialized = true;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        AppUser user = userRepository.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Неверное имя пользователя или пароль");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("username", user.getUsername());
        response.put("role", user.getRole());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Имя пользователя и пароль обязательны");
            return ResponseEntity.badRequest().body(error);
        }

        if (userRepository.findByUsername(username) != null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Пользователь уже существует");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }

        AppUser user = new AppUser(
                UUID.randomUUID().toString(),
                username,
                passwordEncoder.encode(password),
                "user"
        );
        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("username", user.getUsername());
        response.put("role", user.getRole());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = authHeader.replace("Bearer ", "");
            if (!jwtUtil.isValid(token)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            Map<String, Object> response = new HashMap<>();
            response.put("username", jwtUtil.getUsername(token));
            response.put("role", jwtUtil.getRole(token));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}
