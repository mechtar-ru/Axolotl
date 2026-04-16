package com.agent.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP client for MemPalace MCP server.
 * Provides search, add, and knowledge graph operations.
 */
@Component
public class MemPalaceClient {

    private static final Logger log = LoggerFactory.getLogger(MemPalaceClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${axolotl.mempalace.base-url:http://localhost:5890}")
    private String baseUrl;

    @Value("${axolotl.mempalace.enabled:false}")
    private boolean enabled;

    public MemPalaceClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Search MemPalace for relevant memories.
     */
    public List<Map<String, Object>> search(String query, String wing, String room, int limit) {
        if (!enabled) return List.of();
        try {
            var params = new StringBuilder("?query=").append(java.net.URLEncoder.encode(query, "UTF-8"));
            params.append("&limit=").append(limit);
            if (wing != null && !wing.isBlank()) params.append("&wing=").append(java.net.URLEncoder.encode(wing, "UTF-8"));
            if (room != null && !room.isBlank()) params.append("&room=").append(java.net.URLEncoder.encode(room, "UTF-8"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/search" + params))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                List<Map<String, Object>> results = new ArrayList<>();
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("content", item.path("content").asText(""));
                        entry.put("wing", item.path("wing").asText(""));
                        entry.put("room", item.path("room").asText(""));
                        entry.put("score", item.path("score").asDouble(0));
                        results.add(entry);
                    }
                }
                return results;
            }
        } catch (Exception e) {
            log.error("MemPalace search error: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Add a drawer (memory entry) to MemPalace.
     */
    public boolean addDrawer(String wing, String room, String content, String sourceFile) {
        if (!enabled) return false;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("wing", wing);
            body.put("room", room);
            body.put("content", content);
            if (sourceFile != null) body.put("source_file", sourceFile);

            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/add"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.error("MemPalace add error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Build a context block from memory search results for injection into prompts.
     */
    public String searchForContext(String query, int limit) {
        List<Map<String, Object>> results = search(query, null, null, limit);
        if (results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[ПАМЯТЬ — релевантные воспоминания]:\n");
        for (Map<String, Object> r : results) {
            sb.append("- ").append(r.get("content")).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Build graph-structured context for AI: taxonomy + tunnels + search results
     * as a navigable structure, not raw text chunks.
     * Format:
     * [MEMORY GRAPH]:
     * Wings:
     *   axolotl → rooms: [agent-results(3), session-data(1)]
     *   user → rooms: [preferences(2)]
     * Tunnels:
     *   axolotl ↔ user via room "preferences"
     * Relevant nodes:
     *   [axolotl/agent-results] score=0.89: "Agent A produced..."
     *   [axolotl/agent-results] score=0.72: "Agent B analyzed..."
     */
    public String buildGraphContext(String query, int resultLimit) {
        if (!enabled) return "";

        Map<String, Map<String, Integer>> taxonomy = getTaxonomy();
        if (taxonomy.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("[MEMORY GRAPH — структурированный граф знаний]\n\n");

        sb.append("## Структура графа\n");
        sb.append("```\n");

        int wingIndex = 0;
        for (Map.Entry<String, Map<String, Integer>> wingEntry : taxonomy.entrySet()) {
            String wing = wingEntry.getKey();
            int totalDrawers = wingEntry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            sb.append(String.format("Уровень 0: %s (%d drawers)\n", wing, totalDrawers));

            List<Map.Entry<String, Integer>> rooms = new ArrayList<>(wingEntry.getValue().entrySet());
            for (int i = 0; i < rooms.size(); i++) {
                Map.Entry<String, Integer> room = rooms.get(i);
                boolean lastRoom = (i == rooms.size() - 1);
                String prefix = lastRoom ? "└── " : "├── ";
                sb.append(String.format("  %s%s (%d)\n", prefix, room.getKey(), room.getValue()));
            }
            wingIndex++;
        }
        sb.append("```\n\n");

        Set<String> wings = taxonomy.keySet();
        if (wings.size() > 1) {
            sb.append("## Туннели (межкрыльевые связи)\n");
            sb.append("```\n");
            for (String wing : wings) {
                Map<String, Object> tunnelsResult = findTunnels(wing);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tunnels = (List<Map<String, Object>>) tunnelsResult.getOrDefault("tunnels", List.of());
                for (Map<String, Object> tunnel : tunnels) {
                    String room = (String) tunnel.get("room");
                    String connectedWing = (String) tunnel.get("connectedWing");
                    if (room != null && connectedWing != null) {
                        sb.append(String.format("%s ←[%s]→ %s\n", wing, room, connectedWing));
                    }
                }
            }
            sb.append("```\n\n");
        }

        List<Map<String, Object>> results = search(query, null, null, resultLimit);
        if (!results.isEmpty()) {
            sb.append("## Релевантные узлы (по запросу)\n");
            sb.append("| Wing | Room | Score | Preview |\n");
            sb.append("|------|------|-------|--------|\n");
            for (Map<String, Object> r : results) {
                String content = (String) r.get("content");
                String wing = (String) r.get("wing");
                String room = (String) r.get("room");
                Double score = (Double) r.getOrDefault("score", 0.0);
                String preview = content != null && content.length() > 100
                        ? content.substring(0, 100).replace("\n", " ").replace("|", "\\|") + "..."
                        : (content != null ? content.replace("\n", " ").replace("|", "\\|") : "");
                sb.append(String.format("| %s | %s | %.2f | %s |\n", wing, room, score, preview));
            }
            sb.append("\n**Инструкция:** Используй структуру выше для навигации по памяти. ");
            sb.append("Комнаты связаны через туннели. Запроси соседние узлы при необходимости.\n");
        }

        sb.append("\n[КОНЕЦ MEMORY GRAPH]");
        return sb.toString();
    }

    /**
     * Get full taxonomy: wing → room → drawer count.
     */
    public Map<String, Map<String, Integer>> getTaxonomy() {
        if (!enabled) return Map.of();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/taxonomy"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                Map<String, Map<String, Integer>> taxonomy = new HashMap<>();
                if (root.isObject()) {
                    root.fields().forEachRemaining(wingEntry -> {
                        Map<String, Integer> rooms = new HashMap<>();
                        wingEntry.getValue().fields().forEachRemaining(roomEntry -> {
                            rooms.put(roomEntry.getKey(), roomEntry.getValue().asInt(0));
                        });
                        taxonomy.put(wingEntry.getKey(), rooms);
                    });
                }
                return taxonomy;
            }
        } catch (Exception e) {
            log.error("MemPalace taxonomy error: {}", e.getMessage());
        }
        return Map.of();
    }

    /**
     * List all drawers in a wing/room.
     */
    public List<Map<String, Object>> listDrawers(String wing, String room) {
        if (!enabled) return List.of();
        try {
            var params = new StringBuilder("?wing=").append(java.net.URLEncoder.encode(wing, "UTF-8"));
            params.append("&room=").append(java.net.URLEncoder.encode(room, "UTF-8"));
            params.append("&limit=100");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/drawers" + params))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                List<Map<String, Object>> results = new ArrayList<>();
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("id", item.path("id").asText(""));
                        entry.put("wing", item.path("wing").asText(""));
                        entry.put("room", item.path("room").asText(""));
                        entry.put("content", item.path("content").asText(""));
                        entry.put("source_file", item.path("source_file").asText(""));
                        results.add(entry);
                    }
                }
                return results;
            }
        } catch (Exception e) {
            log.error("MemPalace list drawers error: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Find tunnels (rooms connecting two wings).
     */
    public Map<String, Object> findTunnels(String wingA) {
        if (!enabled) return Map.of("tunnels", List.of());
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tunnels?wing_a=" + java.net.URLEncoder.encode(wingA, "UTF-8")))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                List<Map<String, Object>> tunnels = new ArrayList<>();
                if (root.isObject()) {
                    JsonNode tunnelsNode = root.path("tunnels");
                    if (tunnelsNode.isArray()) {
                        for (JsonNode t : tunnelsNode) {
                            Map<String, Object> tunnel = new HashMap<>();
                            tunnel.put("room", t.path("room").asText(""));
                            // Determine connected wing
                            JsonNode wings = t.path("wings");
                            if (wings.isArray()) {
                                for (JsonNode w : wings) {
                                    String wingName = w.asText();
                                    if (!wingName.equals(wingA)) {
                                        tunnel.put("connectedWing", wingName);
                                    }
                                }
                            }
                            tunnels.add(tunnel);
                        }
                    }
                }
                return Map.of("tunnels", tunnels);
            }
        } catch (Exception e) {
            log.error("MemPalace tunnels error: {}", e.getMessage());
        }
        return Map.of("tunnels", List.of());
    }

    public String getNavigationContext(String wing, String room, int depth) {
        if (!enabled) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[MEMORY NAVIGATION — %s/%s (depth=%d)]\n\n", wing, room, depth));

        List<Map<String, Object>> currentDrawers = listDrawers(wing, room);
        if (!currentDrawers.isEmpty()) {
            sb.append("## Текущий узел: ").append(wing).append("/").append(room).append("\n");
            sb.append("| ID | Content | Source |\n");
            sb.append("|----|---------|--------|\n");
            for (Map<String, Object> drawer : currentDrawers) {
                String content = (String) drawer.getOrDefault("content", "");
                String preview = content.length() > 80 ? content.substring(0, 80) + "..." : content;
                String id = (String) drawer.getOrDefault("id", "");
                String source = (String) drawer.getOrDefault("source_file", "-");
                sb.append(String.format("| %s | %s | %s |\n", id, preview.replace("\n", " ").replace("|", "\\|"), source));
            }
        }

        if (depth > 0) {
            Map<String, Object> tunnels = findTunnels(wing);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tunnelList = (List<Map<String, Object>>) tunnels.getOrDefault("tunnels", List.of());
            if (!tunnelList.isEmpty()) {
                sb.append("\n## Связанные узлы (через туннели)\n");
                for (Map<String, Object> tunnel : tunnelList) {
                    String connectedWing = (String) tunnel.get("connectedWing");
                    String tunnelRoom = (String) tunnel.get("room");
                    if (connectedWing != null) {
                        List<Map<String, Object>> neighborDrawers = listDrawers(connectedWing, tunnelRoom);
                        int count = neighborDrawers.size();
                        sb.append(String.format("- [%s/%s] — %d drawers\n", connectedWing, tunnelRoom, count));
                    }
                }
                sb.append("\n**Подсказка:** Для навигации используй команду `memory:nav(wing, room)`\n");
            }
        }

        sb.append("\n[END NAVIGATION]");
        return sb.toString();
    }
}
