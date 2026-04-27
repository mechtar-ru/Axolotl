package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Edge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class WorkflowModifierTest {
    private final WorkflowSelfAnalyzer analyzer = new WorkflowSelfAnalyzer();
    private final WorkflowModifier modifier = new WorkflowModifier();

    @Test
    void testGenerateModificationsForIsolatedNode() {
        WorkflowSchema schema = new WorkflowSchema();
        Node isolated = new Node(); isolated.setId("iso");
        schema.setNodes(List.of(isolated));
        schema.setEdges(List.of());

        WorkflowSelfAnalyzer.WorkflowAnalysisReport report = analyzer.analyze(schema);
        List<WorkflowModifier.WorkflowModification> mods = modifier.generateModifications(report, schema);
        assertEquals(1, mods.size());
        assertEquals(WorkflowModifier.WorkflowModification.ModificationType.REMOVE_ISOLATED_NODE, mods.get(0).getType());
    }

    @Test
    void testIsModificationSafe() {
        WorkflowSchema schema = new WorkflowSchema();
        Node n = new Node(); n.setId("n1");
        schema.setNodes(List.of(n));
        schema.setEdges(List.of());

        WorkflowModifier.WorkflowModification mod = new WorkflowModifier.WorkflowModification();
        mod.setType(WorkflowModifier.WorkflowModification.ModificationType.REMOVE_ISOLATED_NODE);
        mod.setTargetNodeId("n1");
        assertTrue(modifier.isModificationSafe(mod, schema));
    }

    @Test
    void testApplyRemoveIsolatedNode() {
        WorkflowSchema schema = new WorkflowSchema();
        Node isolated = new Node(); isolated.setId("iso");
        schema.setNodes(List.of(isolated));
        schema.setEdges(List.of());

        WorkflowModifier.WorkflowModification mod = new WorkflowModifier.WorkflowModification();
        mod.setType(WorkflowModifier.WorkflowModification.ModificationType.REMOVE_ISOLATED_NODE);
        mod.setTargetNodeId("iso");

        WorkflowSchema modified = modifier.applyModification(schema, mod);
        assertTrue(modified.getNodes().isEmpty());
    }

    @Test
    void testGenerateModificationsForCycle() {
        WorkflowSchema schema = new WorkflowSchema();
        Node a = new Node(); a.setId("a");
        Node b = new Node(); b.setId("b");
        schema.setNodes(List.of(a, b));
        Edge e1 = new Edge(); e1.setId("e1"); e1.setSource("a"); e1.setTarget("b");
        Edge e2 = new Edge(); e2.setId("e2"); e2.setSource("b"); e2.setTarget("a");
        schema.setEdges(List.of(e1, e2));

        WorkflowSelfAnalyzer.WorkflowAnalysisReport report = analyzer.analyze(schema);
        List<WorkflowModifier.WorkflowModification> mods = modifier.generateModifications(report, schema);
        assertTrue(mods.stream().anyMatch(m -> m.getType() == WorkflowModifier.WorkflowModification.ModificationType.BREAK_CYCLE));
    }
}
