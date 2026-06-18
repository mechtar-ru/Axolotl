package com.agent.orchestrator.graph.loader;

import com.agent.orchestrator.graph.model.*;
import com.agent.orchestrator.graph.repository.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static java.util.regex.Pattern.compile;

@Component
public class CodebaseLoader {
    
    private static final Logger log = LoggerFactory.getLogger(CodebaseLoader.class);
    
    // Packages to exclude when loading the Axolotl codebase itself
    // These are tool-internal packages, not user/analyzed code
    private static final List<String> EXCLUDED_PACKAGES = List.of(
        "com.agent.orchestrator.graph",
        "com.agent.orchestrator.config"
    );
    
    private final CodePackageRepository packageRepo;
    private final CodeClassRepository classRepo;
    private final CodeMethodRepository methodRepo;
    private final JavaParser javaParser;
    
    public CodebaseLoader(
            CodePackageRepository packageRepo,
            CodeClassRepository classRepo,
            CodeMethodRepository methodRepo) {
        this.packageRepo = packageRepo;
        this.classRepo = classRepo;
        this.methodRepo = methodRepo;
        
        ParserConfiguration config = new ParserConfiguration()
                .setStoreTokens(true);
        this.javaParser = new JavaParser(config);
    }
    
    /**
     * Parse and load all Java files from a directory.
     * @return number of classes loaded
     */
    @Transactional
    public int loadDirectory(Path directory, String basePackage) throws IOException {
        int loaded = 0;
        
        try (Stream<Path> stream = Files.walk(directory)) {
            List<Path> javaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/test/"))
                    .filter(p -> !isExcludedPath(p))
                    .toList(); // Load all files
            
            log.info("Found {} Java files to parse", javaFiles.size());
            
            for (Path file : javaFiles) {
                try {
                    loadFile(file, basePackage);
                    loaded++;
                } catch (Exception e) {
                    log.warn("Error loading {}: {}", file.getFileName(), e.getMessage(), e);
                }
            }
        }
        
        log.info("Loaded {} classes from {}", loaded, directory);
        return loaded;
    }
    
    private void loadFile(Path file, String basePackage) throws IOException {
        String content = Files.readString(file);
        var parseResult = javaParser.parse(content);
        
        if (!parseResult.isSuccessful()) {
            parseResult.getProblems().forEach(p -> log.warn("Parse problem: {}", p.getMessage()));
            return;
        }
        
        var cu = parseResult.getResult().orElseThrow();
        
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse(basePackage);
        
        // Ensure package exists
        CodePackage pkg = packageRepo.findByPath(packageName)
                .orElseGet(() -> {
                    String[] parts = packageName.split("\\.");
                    String name = parts[parts.length - 1];
                    return packageRepo.save(new CodePackage(name, packageName));
                });
        
        // Parse classes
        for (TypeDeclaration<?> type : cu.getTypes()) {
            if (type instanceof ClassOrInterfaceDeclaration clazz) {
                loadClass(clazz, packageName, file.toString(), pkg);
            }
        }
    }
    
    private CodeClass loadClass(ClassOrInterfaceDeclaration clazz, 
                             String packageName, String filePath, CodePackage parent) {
        String qualifiedName = packageName + "." + clazz.getNameAsString();
        
        // Check if already exists
        var existing = classRepo.findByQualifiedName(qualifiedName).orElse(null);
        if (existing != null) {
            return existing;
        }
        
        CodeClass entity = new CodeClass(
                clazz.getNameAsString(),
                qualifiedName,
                packageName
        );
        entity.setFilePath(filePath);
        entity.setAbstract(clazz.isAbstract());
        entity.setInterface(clazz.isInterface());
        
        String vis = clazz.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .findFirst()
                .orElse("public");
        entity.setVisibility(vis);
        entity.setParentPackage(parent);
        
        entity = classRepo.save(entity);
        
        // Parse methods
        for (MethodDeclaration method : clazz.getMethods()) {
            loadMethod(method, entity);
        }
        
        return entity;
    }
    
    private void loadMethod(MethodDeclaration method, CodeClass parent) {
        // Build parameter string manually
        StringBuilder params = new StringBuilder("(");
        var paramList = method.getParameters();
        for (int i = 0; i < paramList.size(); i++) {
            if (i > 0) params.append(", ");
            var p = paramList.get(i);
            params.append(p.getTypeAsString()).append(" ").append(p.getNameAsString());
        }
        params.append(")");
        
        String signature = parent.getQualifiedName() + "." + method.getNameAsString() + params;
        
        // Check if already exists
        var existing = methodRepo.findBySignature(signature).orElse(null);
        if (existing != null) {
            return;
        }
        
        String returnType = method.getTypeAsString();
        String vis = method.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .findFirst()
                .orElse("public");
        
        CodeMethod m = new CodeMethod(
                method.getNameAsString(),
                signature,
                returnType
        );
        m.setVisibility(vis);
        m.setStatic(method.getModifiers().stream()
                .anyMatch(mod -> mod.getKeyword() == Modifier.Keyword.STATIC));
        m.setLineNumber(method.getBegin().map(l -> l.line).orElse(0));
        m.setParameters(params.toString());
        m.setParentClass(parent);
        
        methodRepo.save(m);
    }
    
    /**
     * Load codebase from backend directory.
     * Uses configurable path from axolotl.graph.codebase-path or default relative path.
     */
    public int loadBackend() {
        String basePath = System.getProperty("user.dir");
        String codebasePath = System.getProperty("axolotl.graph.codebase-path", 
            basePath + "/backend/src/main/java");
        Path backendSrc = Paths.get(codebasePath);
        
        if (!Files.exists(backendSrc)) {
            log.error("Backend source not found: {}", backendSrc);
            return 0;
        }
        
        log.info("Excluding tool-internal packages: {}", EXCLUDED_PACKAGES);
        
        try {
            return loadDirectory(backendSrc, "com.agent.orchestrator");
        } catch (IOException e) {
            log.error("Failed to load backend: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Check if a file path matches an excluded package.
     */
    private boolean isExcludedPath(Path filePath) {
        String path = filePath.toString().replace("\\", "/");
        for (String excluded : EXCLUDED_PACKAGES) {
            String pkgPath = excluded.replace('.', '/');
            if (path.contains(pkgPath)) {
                log.debug("Excluding {} (matches {})", filePath.getFileName(), excluded);
                return true;
            }
        }
        return false;
    }

    /**
     * Public accessor for the exclusion list.
     */
    public List<String> getExcludedPackages() {
        return EXCLUDED_PACKAGES;
    }

    /**
     * Package-private: check if a path matches exclusion (for testing).
     */
    boolean isExcluded(Path filePath) {
        return isExcludedPath(filePath);
    }
}