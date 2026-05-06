package com.agent.orchestrator.graph.api;

import com.agent.orchestrator.graph.context.ContextCurationService;
import com.agent.orchestrator.graph.hasher.CodeEntityHasher;
import com.agent.orchestrator.graph.loader.CodebaseLoader;
import com.agent.orchestrator.graph.loader.ParallelCodebaseImporter;
import com.agent.orchestrator.graph.metrics.GraphMetricsService;
import com.agent.orchestrator.graph.scheduler.BatchPlanner;
import com.agent.orchestrator.graph.search.AstPatternSearchService;
import com.agent.orchestrator.graph.model.CodeClass;
import com.agent.orchestrator.graph.model.CodeMethod;
import com.agent.orchestrator.graph.repository.CodeClassRepository;
import com.agent.orchestrator.graph.repository.CodeMethodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private static final Logger log = LoggerFactory.getLogger(GraphController.class);

    private final CodeClassRepository classRepo;
    private final CodeMethodRepository methodRepo;
    private final CodebaseLoader codebaseLoader;
    private final ParallelCodebaseImporter parallelImporter;
    private final CodeEntityHasher hasher;
    private final AstPatternSearchService astSearch;
    private final BatchPlanner batchPlanner;
    private final ContextCurationService contextCuration;
    private final GraphMetricsService metrics;

    public GraphController(
            CodeClassRepository classRepo,
            CodeMethodRepository methodRepo,
            CodebaseLoader codebaseLoader,
            ParallelCodebaseImporter parallelImporter,
            CodeEntityHasher hasher,
            AstPatternSearchService astSearch,
            BatchPlanner batchPlanner,
            ContextCurationService contextCuration,
            GraphMetricsService metrics) {
        this.classRepo = classRepo;
        this.methodRepo = methodRepo;
        this.codebaseLoader = codebaseLoader;
        this.parallelImporter = parallelImporter;
        this.hasher = hasher;
        this.astSearch = astSearch;
        this.batchPlanner = batchPlanner;
        this.contextCuration = contextCuration;
        this.metrics = metrics;
    }

    @PostMapping("/load")
    public ResponseEntity<Map<String, Object>> loadCodebase(@RequestParam(required = false) String path) {
        try {
            Path targetPath = path != null ? Path.of(path) :
                    Path.of(System.getProperty("user.dir") + "/backend/src/main/java");

            ParallelCodebaseImporter.ImportResult result =
                    parallelImporter.importWithPlanMode(targetPath, "com.agent.orchestrator");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "classes", result.classesLoaded(),
                    "methods", result.methodsLoaded(),
                    "fields", result.fieldsLoaded(),
                    "durationMs", result.durationMs(),
                    "errors", result.errors()
            ));
        } catch (Exception e) {
            log.error("Failed to load codebase", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/class/{hashOrName}")
    public ResponseEntity<?> getClass(@PathVariable String hashOrName) {
        if (hashOrName.length() == 16) {
            Optional<CodeClass> c = classRepo.findByHash(hashOrName);
            return ResponseEntity.ok(c.orElse(null));
        }
        return ResponseEntity.ok(classRepo.findByQualifiedName(hashOrName).orElse(null));
    }

    @PostMapping("/hash/class")
    public ResponseEntity<Map<String, String>> computeClassHash(@RequestBody Map<String, String> request) {
        String packageName = request.get("packageName");
        String className = request.get("className");
        String content = request.get("content");

        com.github.javaparser.JavaParser parser = new com.github.javaparser.JavaParser();
        var result = parser.parse(content);

        if (!result.isSuccessful()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Parse failed"));
        }

        var cu = result.getResult().orElseThrow();
        var clazz = cu.getClassByName(className).orElseThrow();
        String hash = hasher.hashClass(clazz, packageName);

        return ResponseEntity.ok(Map.of("hash", hash));
    }

    @PostMapping("/search/ast")
    public ResponseEntity<Map<String, Object>> searchAst(@RequestBody Map<String, Object> request) {
        String pattern = (String) request.get("pattern");
        String type = (String) request.getOrDefault("type", "method");

        if ("method".equals(type)) {
            List<CodeMethod> methods = astSearch.findMethodsByBodyPattern(pattern);
            return ResponseEntity.ok(Map.of("methods", methods, "count", methods.size()));
        }

        if ("import".equals(type)) {
            List<CodeClass> classes = astSearch.findClassesWithImport(pattern);
            return ResponseEntity.ok(Map.of("classes", classes, "count", classes.size()));
        }

        return ResponseEntity.badRequest().body(Map.of("error", "Unknown type"));
    }

    @PostMapping("/batch/plan")
    public ResponseEntity<Map<String, Object>> planBatch(@RequestBody Map<String, Object> request) {
        String operation = (String) request.get("operation");
        Set<String> completed = new HashSet<>(List.of());

        if ("rename".equals(operation)) {
            String oldName = (String) request.get("oldName");
            String newName = (String) request.get("newName");
            List<BatchPlanner.BatchJob> jobs = batchPlanner.planRenameRefactoring(oldName, newName);
            return ResponseEntity.ok(Map.of("jobs", jobs, "count", jobs.size()));
        }

        List<BatchPlanner.BatchJob> jobs = batchPlanner.findUnblockedEdits(completed);
        return ResponseEntity.ok(Map.of("jobs", jobs, "count", jobs.size()));
    }

    @PostMapping("/curate")
    public ResponseEntity<Map<String, Object>> curateContext(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        int budget = (int) request.getOrDefault("tokenBudget", 2000);
        List<String> recentHashes = (List<String>) request.getOrDefault("recentHashes", List.of());

        ContextCurationService.CurationResult result =
                contextCuration.curateForQuery(query, budget, recentHashes);

        return ResponseEntity.ok(Map.of(
                "prompt", result.prompt(),
                "tokenCount", result.tokenCount(),
                "classHashes", result.classHashes(),
                "methodHashes", result.methodHashes(),
                "strategy", result.strategy()
        ));
    }

    @GetMapping("/impact/{hash}")
    public ResponseEntity<Map<String, Object>> analyzeImpact(@PathVariable String hash) {
        Map<String, List<String>> impact = batchPlanner.analyzeChangeImpact(Set.of(hash));
        return ResponseEntity.ok(Map.of("impact", impact));
    }

    @GetMapping("/tiers")
    public ResponseEntity<Map<String, Object>> getImportTiers() {
        BatchPlanner.BatchPlan plan = batchPlanner.computeImportTiers();
        return ResponseEntity.ok(Map.of(
                "waves", plan.waves().size(),
                "total", plan.totalEntities(),
                "details", plan.waves().stream()
                        .map(w -> w.stream().map(j -> Map.of(
                                "hash", j.hash(),
                                "name", j.qualifiedName(),
                                "tier", j.tier()
                        )).toList())
                        .toList()
        ));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long classCount = classRepo.count();
        List<CodeMethod> methods = methodRepo.findAll();
        return ResponseEntity.ok(Map.of(
                "classes", classCount,
                "methods", methods.size()
        ));
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(metrics.getSummary());
    }

    public Map<String, Object> getStatsInternal() {
        long classCount = classRepo.count();
        List<CodeMethod> methods = methodRepo.findAll();
        BatchPlanner.BatchPlan plan = batchPlanner.computeImportTiers();
        return Map.of(
                "classes", classCount,
                "methods", methods.size(),
                "waves", plan.waves().size(),
                "totalEntities", plan.totalEntities()
        );
    }
}