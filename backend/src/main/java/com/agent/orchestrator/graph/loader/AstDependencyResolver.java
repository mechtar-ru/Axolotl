package com.agent.orchestrator.graph.loader;

import com.agent.orchestrator.graph.model.CodeClass;
import com.agent.orchestrator.graph.model.CodeField;
import com.agent.orchestrator.graph.model.CodeMethod;
import com.agent.orchestrator.graph.repository.CodeClassRepository;
import com.agent.orchestrator.graph.repository.CodeFieldRepository;
import com.agent.orchestrator.graph.repository.CodeMethodRepository;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves class dependencies using proper AST analysis instead of string containment.
 *
 * Analyzes import declarations, type references in extends/implements clauses,
 * field types, method parameter/return types, method body type references,
 * and constructor invocations to build an accurate dependency graph.
 */
@Component
public class AstDependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(AstDependencyResolver.class);

    /** Maps fully-qualified class name -> CodeClass entity for all loaded classes */
    private final Map<String, CodeClass> classRegistry = new HashMap<>();

    /** Maps simple class name -> Set of fully-qualified names (handles ambiguous names) */
    private final Map<String, Set<String>> simpleToQualified = new HashMap<>();

    public AstDependencyResolver(CodeClassRepository classRepo,
                                 CodeMethodRepository methodRepo,
                                 CodeFieldRepository fieldRepo) {
        buildRegistry(classRepo, methodRepo, fieldRepo);
    }

    // Package-private constructor for testing
    AstDependencyResolver(Map<String, CodeClass> registry) {
        this.classRegistry.putAll(registry);
        for (CodeClass c : registry.values()) {
            String simpleName = c.getName();
            simpleToQualified.computeIfAbsent(simpleName, k -> new HashSet<>()).add(c.getQualifiedName());
        }
    }

    public Map<String, CodeClass> getClassRegistry() {
        return Collections.unmodifiableMap(classRegistry);
    }

    private void buildRegistry(CodeClassRepository classRepo,
                                CodeMethodRepository methodRepo,
                                CodeFieldRepository fieldRepo) {
        // Load all classes into registry
        for (CodeClass c : classRepo.findAll()) {
            classRegistry.put(c.getQualifiedName(), c);
            String simpleName = c.getName();
            simpleToQualified.computeIfAbsent(simpleName, k -> new HashSet<>()).add(c.getQualifiedName());
        }
    }

    /**
     * Resolve dependencies for a single class using its AST body.
     * Returns the set of class qualified names that this class depends on.
     */
    public Set<String> resolveDependencies(CodeClass clazz) {
        Set<String> dependencies = new HashSet<>();
        if (clazz.getAstBody() == null || clazz.getAstBody().isEmpty()) {
            return dependencies;
        }

        try {
            CompilationUnit cu = StaticJavaParser.parse(clazz.getAstBody());
            if (cu == null) return dependencies;

            String packageName = clazz.getPackageName() != null ? clazz.getPackageName() : "";
            Set<String> allImports = resolveAllImports(cu, packageName);

            // 1. Resolve extends clause
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(typeDecl -> {
                typeDecl.getExtendedTypes().forEach(extRef -> {
                    String resolved = resolveType(extRef.getNameAsString(), allImports, packageName);
                    if (resolved != null) dependencies.add(resolved);
                });

                // 2. Resolve implements/interface extends
                typeDecl.getImplementedTypes().forEach(implRef -> {
                    String resolved = resolveType(implRef.getNameAsString(), allImports, packageName);
                    if (resolved != null) dependencies.add(resolved);
                });

                // 3. Resolve annotations on the class
                typeDecl.getAnnotations().forEach(ann -> {
                    String resolved = resolveType(ann.getNameAsString(), allImports, packageName);
                    if (resolved != null) dependencies.add(resolved);
                });
            });

            // 4. Resolve field types
            cu.findAll(FieldDeclaration.class).forEach(field -> {
                String type = field.getCommonType().asString();
                String resolved = resolveType(type, allImports, packageName);
                if (resolved != null) dependencies.add(resolved);
                // Also check generic type arguments: List<String> -> String, Map<String, User> -> String, User
                field.getCommonType().findAll(ClassOrInterfaceType.class).forEach(t -> {
                    String genericResolved = resolveType(t.getNameAsString(), allImports, packageName);
                    if (genericResolved != null && !genericResolved.equals(resolved)) {
                        dependencies.add(genericResolved);
                    }
                    // Resolve type arguments recursively
                    t.getTypeArguments().ifPresent(args ->
                        args.forEach(arg -> {
                            String argResolved = resolveTypeFromType(arg, allImports, packageName);
                            if (argResolved != null) dependencies.add(argResolved);
                        })
                    );
                });
            });

            // 5. Resolve method signatures (parameters + return types)
            cu.findAll(MethodDeclaration.class).forEach(method -> {
                // Return type
                String returnType = method.getTypeAsString();
                String resolved = resolveType(returnType, allImports, packageName);
                if (resolved != null) dependencies.add(resolved);

                // Parameter types
                method.getParameters().forEach(param -> {
                    String paramType = param.getTypeAsString();
                    String paramResolved = resolveType(paramType, allImports, packageName);
                    if (paramResolved != null) dependencies.add(paramResolved);
                });

                // Thrown exceptions
                method.getThrownExceptions().forEach(exc -> {
                    String excResolved = resolveType(exc.asString(), allImports, packageName);
                    if (excResolved != null) dependencies.add(excResolved);
                });

                // 6. Method body: resolve local variable types, method call targets, object creation
                if (method.getBody().isPresent()) {
                    resolveBodyDependencies(method.getBody().get(), allImports, packageName, dependencies);
                }
            });

            // 7. Resolve constructor bodies
            cu.findAll(ConstructorDeclaration.class).forEach(ctor -> {
                BlockStmt ctorBody = ctor.getBody();
                if (ctorBody != null) {
                    resolveBodyDependencies(ctorBody, allImports, packageName, dependencies);
                }
            });

            // Remove self-reference
            dependencies.remove(clazz.getQualifiedName());

        } catch (Exception e) {
            log.warn("AST resolution failed for {}: {}", clazz.getQualifiedName(), e.getMessage());
        }

        return dependencies;
    }

    /**
     * Resolve dependencies inside a method/constructor body.
     */
    private void resolveBodyDependencies(Node body, Set<String> allImports, String packageName, Set<String> deps) {
        // Object creation: new ArrayList<>() -> java.util.ArrayList
        body.findAll(ObjectCreationExpr.class).forEach(creation -> {
            String resolved = resolveType(creation.getType().asString(), allImports, packageName);
            if (resolved != null) deps.add(resolved);
            // Generic type args in creation
            creation.getType().getTypeArguments().ifPresent(args ->
                args.forEach(arg -> {
                    String argResolved = resolveTypeFromType(arg, allImports, packageName);
                    if (argResolved != null) deps.add(argResolved);
                })
            );
        });

        // Method call expressions on qualified names: someObj.someMethod()
        // This catches patterns like Collections.sort(), factory.create(), etc.
        body.findAll(MethodCallExpr.class).forEach(call -> {
            // Check if the scope is a type reference (e.g., Collections.sort)
            call.getScope().ifPresent(scope -> {
                if (scope.isNameExpr()) {
                    String scopeName = scope.asNameExpr().getNameAsString();
                    // Skip common Java/lang types
                    if (!isJavaLangType(scopeName)) {
                        String resolved = resolveType(scopeName, allImports, packageName);
                        if (resolved != null) deps.add(resolved);
                    }
                }
            });
        });

        // Type reference in casts: (User) obj -> User
        body.findAll(CastExpr.class).forEach(cast -> {
            String resolved = resolveTypeFromType(cast.getType(), allImports, packageName);
            if (resolved != null) deps.add(resolved);
        });

        // Type reference in instanceof: obj instanceof User -> User
        body.findAll(InstanceOfExpr.class).forEach(inst -> {
            String resolved = resolveTypeFromType(inst.getType(), allImports, packageName);
            if (resolved != null) deps.add(resolved);
        });

        // Catch clause types: catch (IOException e) -> IOException
        body.findAll(CatchClause.class).forEach(catchClause -> {
            String resolved = resolveType(catchClause.getParameter().getType().asString(), allImports, packageName);
            if (resolved != null) deps.add(resolved);
        });

        // Annotation references in methods
        body.findAll(AnnotationDeclaration.class).forEach(ann -> {
            // Annotations used inside methods (rare but possible with @Suppress)
        });

        // Lambdas and method references that reference other types
        body.findAll(LambdaExpr.class).forEach(lambda -> {
            lambda.getParameters().forEach(param -> {
                String paramType = param.getType().asString();
                String resolved = resolveType(paramType, allImports, packageName);
                if (resolved != null) deps.add(resolved);
            });
        });
    }

    /**
     * Collect all imports from a compilation unit, including implicit java.lang.*
     */
    private Set<String> resolveAllImports(CompilationUnit cu, String packageName) {
        Set<String> imports = new HashSet<>();

        // Implicit: java.lang.* is always available
        imports.add("java.lang");

        // Default import: same package
        if (!packageName.isEmpty()) {
            imports.add(packageName + ".*");
        }

        // Explicit imports
        for (ImportDeclaration imp : cu.getImports()) {
            imports.add(imp.getNameAsString());
        }

        return imports;
    }

    /**
     * Given a type name and the set of imports, resolve to a fully-qualified class name.
     * Returns null if the type cannot be resolved (e.g., primitive types, java core types not in registry, or ambiguous).
     */
    private String resolveType(String typeName, Set<String> allImports, String packageName) {
        if (typeName == null || typeName.isEmpty()) return null;

        // Skip primitives
        if (isPrimitive(typeName)) return null;

        // Skip array notation
        String baseType = typeName.replaceAll("\\[\\]$", "");

        // Skip var
        if (baseType.equals("var")) return null;

        // If it's a fully-qualified name
        if (baseType.contains(".")) {
            return classRegistry.containsKey(baseType) ? baseType : null;
        }

        // Try simple name lookup
        String lastSegment = baseType.contains(".") ? baseType.substring(baseType.lastIndexOf('.') + 1) : baseType;
        Set<String> candidates = simpleToQualified.get(lastSegment);

        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.iterator().next();

        // Multiple candidates: try to match via imports
        for (String candidate : candidates) {
            // Direct import match
            for (String imp : allImports) {
                if (imp.endsWith(".*")) {
                    String pkg = imp.substring(0, imp.length() - 2);
                    if (candidate.startsWith(pkg + ".")) return candidate;
                } else if (imp.equals(candidate)) {
                    return candidate;
                }
            }
            // Same package
            if (candidate.startsWith(packageName + ".")) return candidate;
        }

        // Still ambiguous: check java.lang
        for (String candidate : candidates) {
            if (candidate.startsWith("java.lang.")) return candidate;
        }

        // Cannot resolve ambiguous name — return null rather than guess
        log.debug("Ambiguous type resolution for '{}': candidates {}", baseType, candidates);
        return null;
    }

    private String resolveTypeFromType(com.github.javaparser.ast.type.Type type,
                                        Set<String> allImports, String packageName) {
        if (type == null) return null;
        if (type.isPrimitiveType()) return null;
        if (type.isClassOrInterfaceType()) {
            return resolveType(type.asString(), allImports, packageName);
        }
        if (type.isArrayType()) {
            return resolveTypeFromType(type.asArrayType().getComponentType(), allImports, packageName);
        }
        return null;
    }

    private boolean isPrimitive(String type) {
        return switch (type) {
            case "byte", "short", "int", "long", "float", "double", "boolean", "char", "void" -> true;
            default -> false;
        };
    }

    private boolean isJavaLangType(String name) {
        return classRegistry.containsKey("java.lang." + name);
    }

    /**
     * Resolve dependencies for all classes and update them in the registry.
     * Handles transitive dependencies by iterating until stable.
     *
     * @return map of qualifiedName -> resolved dependencies (as CodeClass objects)
     */
    public Map<String, Set<CodeClass>> resolveAllDependencies() {
        long start = System.currentTimeMillis();

        // Phase 1: Direct dependencies
        Map<String, Set<String>> directDeps = new HashMap<>();
        for (CodeClass clazz : classRegistry.values()) {
            Set<String> deps = resolveDependencies(clazz);
            directDeps.put(clazz.getQualifiedName(), deps);
        }

        // Phase 2: Compute transitive closure
        Map<String, Set<String>> transitiveDeps = computeTransitiveClosure(directDeps);

        // Phase 2.5: Clean up any self-referential cycles (defense-in-depth)
        int cycleDetected = 0;
        for (Map.Entry<String, Set<String>> entry : transitiveDeps.entrySet()) {
            if (entry.getValue().contains(entry.getKey())) {
                cycleDetected++;
                log.warn("Self-referential cycle detected in dependency graph: {}", entry.getKey());
                entry.getValue().remove(entry.getKey());
            }
        }
        if (cycleDetected > 0) {
            log.warn("Cleaned {} self-referential cycles from dependency graph", cycleDetected);
        }

        // Phase 3: Update class entities with resolved dependencies
        int updated = 0;
        for (CodeClass clazz : classRegistry.values()) {
            Set<String> deps = transitiveDeps.getOrDefault(clazz.getQualifiedName(), Set.of());
            Set<CodeClass> depEntities = new HashSet<>();
            for (String depName : deps) {
                CodeClass depClass = classRegistry.get(depName);
                if (depClass != null) {
                    depEntities.add(depClass);
                }
            }
            if (!depEntities.equals(clazz.getDependencies())) {
                clazz.setDependencies(depEntities);
                updated++;
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Resolved all dependencies: {} classes in {}ms ({} updated)", classRegistry.size(), elapsed, updated);

        // Build result map
        Map<String, Set<CodeClass>> result = new HashMap<>();
        for (CodeClass clazz : classRegistry.values()) {
            result.put(clazz.getQualifiedName(), clazz.getDependencies());
        }
        return result;
    }

    /**
     * Compute transitive closure of dependencies.
     */
    private Map<String, Set<String>> computeTransitiveClosure(Map<String, Set<String>> directDeps) {
        Map<String, Set<String>> result = new HashMap<>();

        for (String className : directDeps.keySet()) {
            Set<String> allDeps = new HashSet<>();
            Queue<String> queue = new LinkedList<>(directDeps.getOrDefault(className, Set.of()));
            while (!queue.isEmpty()) {
                String dep = queue.poll();
                if (allDeps.add(dep)) {
                    // Only follow transitive deps that are in our class registry
                    if (directDeps.containsKey(dep)) {
                        for (String transitive : directDeps.get(dep)) {
                            if (!allDeps.contains(transitive) && !transitive.equals(className)) {
                                queue.add(transitive);
                            }
                        }
                    }
                }
            }
            result.put(className, allDeps);
        }

        return result;
    }
}