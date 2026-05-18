package com.agent.orchestrator.graph.search;

import com.agent.orchestrator.graph.model.CodeMethod;
import com.agent.orchestrator.graph.model.CodeClass;
import com.agent.orchestrator.graph.repository.CodeMethodRepository;
import com.agent.orchestrator.graph.repository.CodeClassRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AstPatternSearchService {

    private static final Logger log = LoggerFactory.getLogger(AstPatternSearchService.class);

    private final CodeMethodRepository methodRepo;
    private final CodeClassRepository classRepo;

    public AstPatternSearchService(
            CodeMethodRepository methodRepo,
            CodeClassRepository classRepo) {
        this.methodRepo = methodRepo;
        this.classRepo = classRepo;
    }

    public List<CodeMethod> findMethodsByBodyPattern(String pattern) {
        return methodRepo.findByBodyContaining(pattern);
    }

    public List<CodeMethod> findMethodCalls(String methodName) {
        return methodRepo.findByNameContaining(methodName);
    }

    public List<CodeClass> findClassesWithImport(String importPattern) {
        return classRepo.findByImportsContaining(importPattern);
    }

    public List<CodeMethod> findByReturnType(String returnType) {
        return methodRepo.findByReturnType(returnType);
    }

    public List<CodeMethod> findMethodsWithAnnotation(String annotationName) {
        return methodRepo.findByDescriptionContaining("@" + annotationName);
    }

    public List<CodeClass> findByDependencyChain(String startClass, int depth) {
        return classRepo.findByQualifiedNameContaining(startClass);
    }

    public Map<String, Object> searchWithContext(String query, int limit) {
        String[] tokens = query.toLowerCase().split("\\s+");
        String token = tokens[0];

        List<CodeClass> classes = classRepo.findByNameContainingOrQualifiedNameContaining(token, token);

        Map<String, Object> response = new HashMap<>();
        response.put("results", classes);
        response.put("query", query);
        response.put("tokens", tokens.length);

        return response;
    }
}