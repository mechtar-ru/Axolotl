package com.agent.orchestrator.model;

import lombok.Data;

@Data
public class AppUser {
    private String id;
    private String username;
    private String password;
    private String role; // admin, user, viewer

    public AppUser() {}

    public AppUser(String id, String username, String password, String role) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.role = role;
    }
}
