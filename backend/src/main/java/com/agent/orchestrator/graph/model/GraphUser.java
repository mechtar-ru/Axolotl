package com.agent.orchestrator.graph.model;

import org.springframework.data.neo4j.core.schema.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Node("User")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GraphUser {

    @Id
    @Property("id")
    private String id;

    @Property("username")
    private String username;

    @Property("password")
    private String password;

    @Property("role")
    private String role;


    public GraphUser(String id, String username, String password, String role) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.role = role;
    }




}
