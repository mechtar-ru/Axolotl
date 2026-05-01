package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;

import java.util.*;
import java.util.stream.Collectors;


public class WorkflowMetaLearner {
    @FunctionalInterface
    public interface WorkflowFitnessFunction {
        double evaluate(WorkflowSchema workflow);
    }

    private final WorkflowAnalyzer workflowAnalyzer;
    private Map<String, NodePerformanceMetrics> executionHistory;
    private WorkflowFitnessFunction fitnessFunction;

    public WorkflowMetaLearner(WorkflowAnalyzer workflowAnalyzer) {
        this.workflowAnalyzer = workflowAnalyzer;
    }

    public void setExecutionHistory(Map<String, NodePerformanceMetrics> executionHistory) {
        this.executionHistory = executionHistory;
    }

    public void setFitnessFunction(WorkflowFitnessFunction fitnessFunction) {
        this.fitnessFunction = fitnessFunction;
    }
    
    public WorkflowFitnessFunction getFitnessFunction() {
        return this.fitnessFunction;
    }

    public List<WorkflowSchema> generateMutations(WorkflowSchema original) {
        if (executionHistory == null) {
            throw new IllegalStateException("Execution history not set");
        }
        List<WorkflowSchema> mutations = new ArrayList<>();
        List<String> underutilizedNodeIds = workflowAnalyzer.findUnderutilizedNodes(executionHistory);

        for (String nodeId : underutilizedNodeIds) {
            WorkflowSchema mutated = removeNode(original, nodeId);
            if (mutated != null) {
                mutations.add(mutated);
            }
        }
        return mutations;
    }

    private WorkflowSchema removeNode(WorkflowSchema original, String nodeId) {
        Node nodeToRemove = original.getNodes().stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst()
                .orElse(null);
        if (nodeToRemove == null) {
            return null;
        }

        List<Node> newNodes = original.getNodes().stream()
                .filter(n -> !n.getId().equals(nodeId))
                .collect(Collectors.toList());

        List<Edge> newEdges = original.getEdges().stream()
                .filter(e -> !e.getSource().equals(nodeId) && !e.getTarget().equals(nodeId))
                .collect(Collectors.toList());

        WorkflowSchema mutated = new WorkflowSchema();
        mutated.setId(original.getId() + "_mutated_" + nodeId);
        mutated.setName(original.getName());
        mutated.setNodes(newNodes);
        mutated.setEdges(newEdges);
        return mutated;
    }

    public WorkflowSchema crossover(WorkflowSchema parent1, WorkflowSchema parent2) {
        List<Node> combinedNodes = new ArrayList<>(parent1.getNodes());
        parent2.getNodes().stream()
                .filter(n -> !parent1.getNodes().contains(n))
                .forEach(combinedNodes::add);

        List<Edge> combinedEdges = new ArrayList<>();
        addValidEdges(parent1, combinedNodes, combinedEdges);
        addValidEdges(parent2, combinedNodes, combinedEdges);

        WorkflowSchema child = new WorkflowSchema();
        child.setId(parent1.getId() + "_" + parent2.getId() + "_child");
        child.setName(parent1.getName() + " + " + parent2.getName());
        child.setNodes(combinedNodes);
        child.setEdges(combinedEdges);
        return child;
    }

    private void addValidEdges(WorkflowSchema parent, List<Node> combinedNodes, List<Edge> combinedEdges) {
        for (Edge e : parent.getEdges()) {
            boolean sourceExists = combinedNodes.stream().anyMatch(n -> n.getId().equals(e.getSource()));
            boolean targetExists = combinedNodes.stream().anyMatch(n -> n.getId().equals(e.getTarget()));
            if (sourceExists && targetExists) {
                combinedEdges.add(e);
            }
        }
    }

    public WorkflowSchema optimize(WorkflowSchema initial, int maxIterations) {
        if (fitnessFunction == null) {
            throw new IllegalStateException("Fitness function not set");
        }
        if (executionHistory == null) {
            throw new IllegalStateException("Execution history not set");
        }

        WorkflowSchema best = initial;
        double bestFitness = fitnessFunction.evaluate(best);

        for (int i = 0; i < maxIterations; i++) {
            List<WorkflowSchema> mutations = generateMutations(best);
            for (WorkflowSchema mutation : mutations) {
                double fitness = fitnessFunction.evaluate(mutation);
                if (fitness > bestFitness) {
                    best = mutation;
                    bestFitness = fitness;
                }
            }
        }
        return best;
    }
}
