package com.agent.orchestrator.repository;

import com.agent.orchestrator.model.ShareLink;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class ShareLinkRepository {
    private final Map<String, ShareLink> linksById = new ConcurrentHashMap<>();
    private final Map<String, ShareLink> linksByToken = new ConcurrentHashMap<>();

    public ShareLink save(ShareLink link) {
        linksById.put(link.getId(), link);
        linksByToken.put(link.getToken(), link);
        return link;
    }

    public Optional<ShareLink> findById(String id) {
        return Optional.ofNullable(linksById.get(id));
    }

    public Optional<ShareLink> findByToken(String token) {
        ShareLink link = linksByToken.get(token);
        if (link != null && link.getExpiresAt() != null && link.getExpiresAt().isBefore(java.time.Instant.now())) {
            delete(link.getId());
            return Optional.empty();
        }
        return Optional.ofNullable(link);
    }

    public List<ShareLink> findBySchemaId(String schemaId) {
        return linksById.values().stream()
                .filter(l -> schemaId.equals(l.getSchemaId()))
                .toList();
    }

    public void delete(String id) {
        ShareLink link = linksById.remove(id);
        if (link != null) {
            linksByToken.remove(link.getToken());
        }
    }
}
