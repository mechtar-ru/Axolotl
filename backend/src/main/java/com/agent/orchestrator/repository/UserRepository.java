package com.agent.orchestrator.repository;

import com.agent.orchestrator.graph.model.GraphUser;
import com.agent.orchestrator.graph.repository.Neo4jUserRepository;
import com.agent.orchestrator.model.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final Neo4jUserRepository neo4jRepo;

    public UserRepository(Neo4jUserRepository neo4jRepo) {
        this.neo4jRepo = neo4jRepo;
    }

    public AppUser findByUsername(String username) {
        try {
            return neo4jRepo.findByUsername(username).map(this::toPoco).orElse(null);
        } catch (Exception e) {
            log.error("Error finding user: {}", e.getMessage(), e);
            return null;
        }
    }

    public List<AppUser> findAll() {
        List<AppUser> users = new ArrayList<>();
        try {
            for (GraphUser g : neo4jRepo.findAll()) {
                users.add(toPoco(g));
            }
        } catch (Exception e) {
            log.error("Error listing users: {}", e.getMessage(), e);
        }
        return users;
    }

    public void save(AppUser user) {
        try {
            neo4jRepo.save(toGraph(user));
        } catch (Exception e) {
            log.error("Error saving user: {}", e.getMessage(), e);
        }
    }

    private GraphUser toGraph(AppUser u) {
        return new GraphUser(u.getId(), u.getUsername(), u.getPassword(), u.getRole());
    }

    private AppUser toPoco(GraphUser g) {
        return new AppUser(g.getId(), g.getUsername(), g.getPassword(), g.getRole());
    }
}
