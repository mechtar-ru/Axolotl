package com.agent.orchestrator.graph.loader;

import com.agent.orchestrator.graph.hasher.CodeEntityHasher;
import com.agent.orchestrator.graph.metrics.GraphMetricsService;
import com.agent.orchestrator.graph.model.*;
import com.agent.orchestrator.graph.repository.*;
import com.agent.orchestrator.graph.scheduler.BatchPlanner;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import jakarta.annotation.PreDestroy;

@Component
public class ParallelCodebaseImporter {

    private static final Logger log = LoggerFactory.getLogger(ParallelCodebaseImporter.class);
    private static final int PARALLELISM = 8;
    private static final int BATCH_SIZE = 20;

    private final CodePackageRepository packageRepo;
    private final CodeClassRepository classRepo;
    private final CodeMethodRepository methodRepo;
    private final CodeFieldRepository fieldRepo;
    private final CodeEntityHasher hasher;
    private final BatchPlanner batchPlanner;
    private final GraphMetricsService metrics;
    private final JavaParser javaParser;
    private final ExecutorService executor;
    private final AstDependencyResolver astDependencyResolver;

    public ParallelCodebaseImporter(
            CodePackageRepository packageRepo,
            CodeClassRepository classRepo,
            CodeMethodRepository methodRepo,
            CodeFieldRepository fieldRepo,
            CodeEntityHasher hasher,
            BatchPlanner batchPlanner,
            GraphMetricsService metrics,
            AstDependencyResolver astDependencyResolver) {
        this.packageRepo = packageRepo;
        this.classRepo = classRepo;
        this.methodRepo = methodRepo;
        this.fieldRepo = fieldRepo;
        this.hasher = hasher;
        this.batchPlanner = batchPlanner;
        this.metrics = metrics;
        this.astDependencyResolver = astDependencyResolver;

        ParserConfiguration config = new ParserConfiguration().setStoreTokens(true);
        this.javaParser = new JavaParser(config);
        this.executor = Executors.newFixedThreadPool(PARALLELISM);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Expose the class registry for other components that need direct access.
     */
    public Map<String, CodeClass> getClassRegistry() {
        return astDependencyResolver.getClassRegistry();
    }

    public record ImportResult(
            int classesLoaded,
            int methodsLoaded,
            int fieldsLoaded,
            long durationMs,
            List<String> errors
    ) {}

    public ImportResult importWithPlanMode(Path directory, String basePackage) throws IOException {
        long start = System.currentTimeMillis();

        List<Path> javaFiles = discoverJavaFiles(directory);
        log.info("Discovered {} Java files", javaFiles.size());

        List<BatchPlanner.BatchJob> plan = batchPlanner.computeImportTiers().waves().stream()
                .flatMap(List::stream)
                .toList();

        log.info("Import plan: {} tiers, {} total entities", batchPlanner.computeImportTiers().waves().size(), plan.size());

        AtomicInteger classesLoaded = new AtomicInteger(0);
        AtomicInteger methodsLoaded = new AtomicInteger(0);
        AtomicInteger fieldsLoaded = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        List<List<Path>> batches = partition(javaFiles, BATCH_SIZE);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (List<Path> batch : batches) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                for (Path file : batch) {
                    try {
                        ParsedFileResult result = parseFile(file, basePackage);
                        classesLoaded.addAndGet(result.classes());
                        methodsLoaded.addAndGet(result.methods());
                        fieldsLoaded.addAndGet(result.fields());
                    } catch (Exception e) {
                        errors.add(file.toString() + ": " + e.getMessage());
                        log.warn("Error parsing {}: {}", file.getFileName(), e.getMessage(), e);
                    }
                }
            }, executor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(5, TimeUnit.MINUTES)
                .join();

        createDependencies();

        long duration = System.currentTimeMillis() - start;
        log.info("Import complete: {} classes, {} methods, {} fields in {}ms",
                classesLoaded.get(), methodsLoaded.get(), fieldsLoaded.get(), duration);

        if (metrics != null) {
            metrics.recordImport(duration);
        }

        return new ImportResult(
                classesLoaded.get(),
                methodsLoaded.get(),
                fieldsLoaded.get(),
                duration,
                errors
        );
    }

    private ParsedFileResult parseFile(Path file, String basePackage) throws IOException {
        String content = Files.readString(file);
        var parseResult = javaParser.parse(content);

        if (!parseResult.isSuccessful()) {
            return new ParsedFileResult(0, 0, 0);
        }

        var cu = parseResult.getResult().orElseThrow();

        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse(basePackage);

        CodePackage pkg = packageRepo.findByPath(packageName)
                .orElseGet(() -> {
                    String[] parts = packageName.split("\\.");
                    String name = parts[parts.length - 1];
                    return packageRepo.save(new CodePackage(name, packageName));
                });

        String imports = cu.getImports().stream()
                .map(i -> i.getNameAsString())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        int classes = 0, methods = 0, fields = 0;

        for (TypeDeclaration<?> type : cu.getTypes()) {
            if (type instanceof ClassOrInterfaceDeclaration clazz) {
                classes++;
                classes += parseAndSaveClass(clazz, packageName, file.toString(), pkg, imports, content);
            }
        }

        return new ParsedFileResult(classes, methods, fields);
    }

    private int parseAndSaveClass(ClassOrInterfaceDeclaration clazz,
                                   String packageName, String filePath,
                                   CodePackage pkg, String imports, String fullContent) {
        String qualifiedName = packageName + "." + clazz.getNameAsString();

        String hash = hasher.hashClass(clazz, packageName);

        CodeClass entity = classRepo.findByQualifiedName(qualifiedName).orElse(null);
        if (entity != null) {
            entity.setHash(hash);
            entity.setUpdatedAt(java.time.Instant.now());
            entity = classRepo.save(entity);
            return 0;
        }

        entity = new CodeClass(clazz.getNameAsString(), qualifiedName, packageName);
        entity.setHash(hash);
        entity.setFilePath(filePath);
        entity.setAbstract(clazz.isAbstract());
        entity.setInterface(clazz.isInterface());
        entity.setImports(imports);
        entity.setParentPackage(pkg);

        String vis = clazz.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .findFirst()
                .orElse("public");
        entity.setVisibility(vis);

        String astBody = extractAstBody(clazz, fullContent);
        entity.setAstBody(astBody);

        entity = classRepo.save(entity);

        int methodCount = 0;
        for (MethodDeclaration method : clazz.getMethods()) {
            parseAndSaveMethod(method, entity);
            methodCount++;
        }

        for (FieldDeclaration field : clazz.getFields()) {
            parseAndSaveField(field, entity);
        }

        return methodCount;
    }

    private void parseAndSaveMethod(MethodDeclaration method, CodeClass parent) {
        StringBuilder params = new StringBuilder("(");
        var paramList = method.getParameters();
        for (int i = 0; i < paramList.size(); i++) {
            if (i > 0) params.append(", ");
            var p = paramList.get(i);
            params.append(p.getTypeAsString()).append(" ").append(p.getNameAsString());
        }
        params.append(")");

        String signature = parent.getQualifiedName() + "." + method.getNameAsString() + params;
        String hash = hasher.hashMethod(method, parent.getQualifiedName());

        var existing = methodRepo.findBySignature(signature).orElse(null);
        if (existing != null) {
            existing.setHash(hash);
            methodRepo.save(existing);
            return;
        }

        String returnType = method.getTypeAsString();
        String vis = method.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .findFirst()
                .orElse("public");

        CodeMethod m = new CodeMethod(method.getNameAsString(), signature, returnType);
        m.setHash(hash);
        m.setVisibility(vis);
        m.setStatic(method.getModifiers().stream()
                .anyMatch(mod -> mod.getKeyword() == Modifier.Keyword.STATIC));
        m.setLineNumber(method.getBegin().map(l -> l.line).orElse(0));
        m.setParameters(params.toString());

        if (method.getBody().isPresent()) {
            String body = method.getBody().get().toString();
            m.setBody(body);
        }

        m.setParentClass(parent);
        methodRepo.save(m);
    }

    private void parseAndSaveField(FieldDeclaration field, CodeClass parent) {
        for (var var : field.getVariables()) {
            String signature = parent.getQualifiedName() + "." + var.getNameAsString();
            String hash = hasher.hashField(field, parent.getQualifiedName());

            CodeField f = new CodeField(var.getNameAsString(), field.getCommonType().asString());
            f.setHash(hash);
            f.setSignature(signature);

            String vis = field.getModifiers().stream()
                    .map(m -> m.getKeyword().asString())
                    .findFirst()
                    .orElse("private");
            f.setVisibility(vis);

            f.setStatic(field.getModifiers().stream()
                    .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC));
            f.setFinal(field.getModifiers().stream()
                    .anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL));

            if (var.getInitializer().isPresent()) {
                f.setInitializer(var.getInitializer().get().toString());
            }

            f.setLineNumber(field.getBegin().map(l -> l.line).orElse(0));
            f.setParentClass(parent);

            fieldRepo.save(f);
        }
    }

    private String extractAstBody(ClassOrInterfaceDeclaration clazz, String fullContent) {
        if (clazz.getRange().isEmpty()) return "";
        var range = clazz.getRange().get();
        String[] lines = fullContent.split("\n");
        if (range.end.line > lines.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = range.begin.line - 1; i < range.end.line && i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    private void createDependencies() {
        try {
            Map<String, Set<CodeClass>> deps = astDependencyResolver.resolveAllDependencies();
            
            // Cycle detection: check for self-referential cycles
            detectAndLogCycles(deps);
            
            // Save all updated entities in batch
            classRepo.saveAll(astDependencyResolver.getClassRegistry().values());
            log.info("Dependencies resolved using AST analysis");
        } catch (Exception e) {
            log.error("Failed to resolve dependencies: {}", e.getMessage(), e);
        }
    }

    /**
     * Detect and log cycles in the dependency graph.
     * Uses DFS-based cycle detection and breaks self-referential cycles.
     */
    void detectAndLogCycles(Map<String, Set<CodeClass>> deps) {
        int cyclesFound = 0;
        
        for (String className : deps.keySet()) {
            Set<String> visited = new HashSet<>();
            Set<String> recursionStack = new HashSet<>();
            
            if (hasCycle(className, deps, visited, recursionStack)) {
                log.warn("Cycle detected involving class: {}", className);
                cyclesFound++;
                // Break the cycle by removing self-referential dependencies
                Set<CodeClass> classDeps = deps.get(className);
                if (classDeps != null) {
                    classDeps.removeIf(dep -> dep.getQualifiedName().equals(className));
                    log.info("Broken self-reference for: {}", className);
                }
            }
        }
        
        if (cyclesFound > 0) {
            log.info("Cycle detection complete: {} cycles found and broken", cyclesFound);
        }
    }

    private boolean hasCycle(String node, Map<String, Set<CodeClass>> graph,
                              Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(node)) {
            return true;
        }
        if (visited.contains(node)) {
            return false;
        }
        
        visited.add(node);
        recursionStack.add(node);
        
        Set<CodeClass> neighbors = graph.get(node);
        if (neighbors != null) {
            for (CodeClass neighbor : neighbors) {
                if (neighbor != null && hasCycle(neighbor.getQualifiedName(), graph, visited, recursionStack)) {
                    return true;
                }
            }
        }
        
        recursionStack.remove(node);
        return false;
    }

    public Map<String, Set<CodeClass>> getResolvedDependencies() {
        return astDependencyResolver.resolveAllDependencies();
    }

    private List<Path> discoverJavaFiles(Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/test/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList();
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    public record ParsedFileResult(int classes, int methods, int fields) {}
}