package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.ShareLink;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ShareLinkRepository;
import com.agent.orchestrator.service.SchemaService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final ShareLinkRepository shareLinkRepository;
    private final SchemaService schemaService;

    public ShareController(ShareLinkRepository shareLinkRepository, SchemaService schemaService) {
        this.shareLinkRepository = shareLinkRepository;
        this.schemaService = schemaService;
    }

    @PostMapping("/schemas/{schemaId}")
    public Map<String, Object> createShareLink(@PathVariable String schemaId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        WorkflowSchema schema = schemaService.getSchema(schemaId);
        if (schema == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found");
        }

        ShareLink link = new ShareLink(schemaId);

        if (body != null) {
            if (body.containsKey("expiresInDays")) {
                int days = (Integer) body.get("expiresInDays");
                link.setExpiresAt(Instant.now().plusSeconds(days * 86400L));
            }
            if (body.containsKey("readOnly")) {
                link.setReadOnly((Boolean) body.get("readOnly"));
            }
        }

        shareLinkRepository.save(link);

        Map<String, Object> response = new HashMap<>();
        response.put("id", link.getId());
        response.put("token", link.getToken());
        response.put("url", "/shared/" + link.getToken());
        response.put("expiresAt", link.getExpiresAt() != null ? link.getExpiresAt().toString() : null);
        response.put("readOnly", link.isReadOnly());
        response.put("schemaName", schema.getName());

        return response;
    }

    @GetMapping("/schemas/{schemaId}")
    public List<Map<String, Object>> listShareLinks(@PathVariable String schemaId) {
        return shareLinkRepository.findBySchemaId(schemaId).stream()
                .map(link -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("id", link.getId());
                    info.put("token", link.getToken());
                    info.put("url", "/shared/" + link.getToken());
                    info.put("expiresAt", link.getExpiresAt() != null ? link.getExpiresAt().toString() : null);
                    info.put("readOnly", link.isReadOnly());
                    info.put("createdAt", link.getCreatedAt().toString());
                    return info;
                })
                .toList();
    }

    @DeleteMapping("/{linkId}")
    public void deleteShareLink(@PathVariable String linkId) {
        shareLinkRepository.delete(linkId);
    }

    @GetMapping("/t/{token}")
    public Map<String, Object> getSharedSchema(@PathVariable String token) {
        ShareLink link = shareLinkRepository.findByToken(token).orElse(null);
        if (link == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Share link not found or expired");
        }

        WorkflowSchema schema = schemaService.getSchema(link.getSchemaId());
        if (schema == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("schema", schema);
        response.put("readOnly", link.isReadOnly());
        response.put("expiresAt", link.getExpiresAt() != null ? link.getExpiresAt().toString() : null);

        return response;
    }
}
