package com.agent.orchestrator.graph.hasher;

import com.github.javaparser.ast.body.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class CodeEntityHasher {

    public String hashClass(ClassOrInterfaceDeclaration clazz, String packageName) {
        String signature = buildClassSignature(clazz, packageName);
        return sha256(signature);
    }

    public String hashMethod(MethodDeclaration method, String containingClassQualifiedName) {
        String signature = buildMethodSignature(method, containingClassQualifiedName);
        return sha256(signature);
    }

    public String hashField(FieldDeclaration field, String containingClassQualifiedName) {
        String signature = buildFieldSignature(field, containingClassQualifiedName);
        return sha256(signature);
    }

    private String buildClassSignature(ClassOrInterfaceDeclaration clazz, String packageName) {
        StringBuilder sb = new StringBuilder();
        sb.append("class:").append(packageName).append(".").append(clazz.getNameAsString());

        if (clazz.isInterface()) {
            sb.append(":interface");
        }
        if (clazz.isAbstract()) {
            sb.append(":abstract");
        }

        clazz.getExtendedTypes().forEach(t -> sb.append(":extends:").append(t.getNameAsString()));
        clazz.getImplementedTypes().forEach(t -> sb.append(":implements:").append(t.getNameAsString()));

        return sb.toString();
    }

    private String buildMethodSignature(MethodDeclaration method, String containingClass) {
        StringBuilder sb = new StringBuilder();
        sb.append("method:").append(containingClass).append(".").append(method.getNameAsString());

        sb.append(":").append(method.getTypeAsString());

        sb.append(":(");
        var params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(params.get(i).getTypeAsString());
        }
        sb.append(")");

        method.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .sorted()
                .forEach(m -> sb.append(":").append(m));

        return sb.toString();
    }

    private String buildFieldSignature(FieldDeclaration field, String containingClass) {
        if (field.getVariables().isEmpty()) {
            return "field:" + containingClass + ":" + field.getCommonType().asString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("field:").append(containingClass).append(".").append(field.getVariable(0).getNameAsString());
        sb.append(":").append(field.getCommonType().asString());

        field.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .sorted()
                .forEach(m -> sb.append(":").append(m));

        return sb.toString();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public String computeStableHash(String entityType, String qualifiedName, String signature) {
        return sha256(entityType + ":" + qualifiedName + ":" + signature);
    }

    /**
     * Check if two different entities produce the same hash (collision detection).
     * This helps detect hash truncation collisions.
     *
     * @param entityType1 The type of the first entity (e.g., "class", "method", "field")
     * @param qualifiedName1 The qualified name of the first entity
     * @param signature1 The signature content for the first entity
     * @param entityType2 The type of the second entity
     * @param qualifiedName2 The qualified name of the second entity
     * @param signature2 The signature content for the second entity
     * @return A CollisionResult indicating whether a collision was detected
     */
    public CollisionResult detectCollision(
            String entityType1, String qualifiedName1, String signature1,
            String entityType2, String qualifiedName2, String signature2) {
        String hash1 = computeStableHash(entityType1, qualifiedName1, signature1);
        String hash2 = computeStableHash(entityType2, qualifiedName2, signature2);

        boolean collision = hash1.equals(hash2)
                && !(entityType1.equals(entityType2)
                     && qualifiedName1.equals(qualifiedName2)
                     && signature1.equals(signature2));

        return new CollisionResult(hash1, hash2, collision);
    }

    public record CollisionResult(String hash1, String hash2, boolean collisionFound) {
        public String message() {
            if (collisionFound) {
                return "COLLISION DETECTED: " + hash1 + " == " + hash2;
            }
            return "No collision: " + hash1 + " != " + hash2;
        }
    }
}