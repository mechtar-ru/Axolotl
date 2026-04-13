package com.agent.orchestrator.controller;

import com.agent.orchestrator.config.JwtUtil;
import com.agent.orchestrator.model.AppUser;
import com.agent.orchestrator.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
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
        if (userRepository.findByUsername("admin") == null) {
            AppUser admin = new AppUser(
                    UUID.randomUUID().toString(),
                    "admin",
                    passwordEncoder.encode("admin"),
                    "admin"
            );
            userRepository.save(admin);
            log.info("Default admin user created (admin:admin)");
        }
        adminInitialized = true;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        AppUser user = userRepository.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Неверное имя пользователя или пароль");
            return error;
        }

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("username", user.getUsername());
        response.put("role", user.getRole());
        return response;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");
        String role = request.getOrDefault("role", "user");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Имя пользователя и пароль обязательны");
            return error;
        }

        if (userRepository.findByUsername(username) != null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Пользователь уже существует");
            return error;
        }

        AppUser user = new AppUser(
                UUID.randomUUID().toString(),
                username,
                passwordEncoder.encode(password),
                role
        );
        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("username", user.getUsername());
        response.put("role", user.getRole());
        return response;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        Map<String, Object> response = new HashMap<>();
        response.put("username", jwtUtil.getUsername(token));
        response.put("role", jwtUtil.getRole(token));
        return response;
    }
}
