package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ToolSynthesizerTest {
    
    @Test
    void testProcessToolHistoryExtractsSequences() {
        ToolSynthesizer synthesizer = new ToolSynthesizer();
        
        List<ToolSynthesizer.ToolInvocation> invocations = Arrays.asList(
            new ToolSynthesizer.ToolInvocation("grep", Arrays.asList("-i", "test"), "session1"),
            new ToolSynthesizer.ToolInvocation("sort", null, "session1"),
            new ToolSynthesizer.ToolInvocation("uniq", null, "session1"),
            new ToolSynthesizer.ToolInvocation("wc", Arrays.asList("-l"), "session2")
        );
        
        synthesizer.processToolHistory(invocations);
        
        Map<String, ToolSynthesizer.ToolSequence> sequences = 
            synthesizer.extractSequences(invocations);
        
        assertFalse(sequences.isEmpty());
    }
    
    @Test
    void testSynthesizeFromSequence() {
        ToolSynthesizer synthesizer = new ToolSynthesizer();
        
        List<ToolSynthesizer.ToolInvocation> invocations = Arrays.asList(
            new ToolSynthesizer.ToolInvocation("grep", Arrays.asList("-i", "test"), "session1"),
            new ToolSynthesizer.ToolInvocation("sort", null, "session1"),
            new ToolSynthesizer.ToolInvocation("uniq", null, "session1")
        );
        
        ToolSynthesizer.ToolSequence sequence = new ToolSynthesizer.ToolSequence(invocations);
        ToolSynthesizer.SynthesizedTool tool = synthesizer.synthesizeFromSequence(sequence);
        
        assertNotNull(tool);
        assertNotNull(tool.getName());
        assertEquals(3, tool.getSourceSequence().size());
        assertNotNull(tool.getImplementation());
        assertTrue(tool.getImplementation().contains("#!/bin/bash"));
    }
    
    @Test
    void testSynthesizedToolContainsToolSequence() {
        ToolSynthesizer synthesizer = new ToolSynthesizer();
        
        List<ToolSynthesizer.ToolInvocation> invocations = Arrays.asList(
            new ToolSynthesizer.ToolInvocation("ls", null, "session"),
            new ToolSynthesizer.ToolInvocation("wc", Arrays.asList("-l"), "session")
        );
        
        ToolSynthesizer.ToolSequence sequence = new ToolSynthesizer.ToolSequence(invocations);
        ToolSynthesizer.SynthesizedTool tool = synthesizer.synthesizeFromSequence(sequence);
        
        List<String> toolNames = tool.getSourceSequence();
        assertEquals("ls", toolNames.get(0));
        assertEquals("wc", toolNames.get(1));
    }
    
    @Test
    void testToolSequenceFrequencyIncrement() {
        List<ToolSynthesizer.ToolInvocation> invocations = Arrays.asList(
            new ToolSynthesizer.ToolInvocation("cmd1", null, "session"),
            new ToolSynthesizer.ToolInvocation("cmd2", null, "session")
        );
        
        ToolSynthesizer.ToolSequence sequence = new ToolSynthesizer.ToolSequence(invocations);
        assertEquals(1, sequence.getFrequency());
        
        sequence.incrementFrequency();
        assertEquals(2, sequence.getFrequency());
    }
    
    @Test
    void testToolInvocationWithInputOutputTypes() {
        ToolSynthesizer.ToolInvocation inv = new ToolSynthesizer.ToolInvocation(
            "transform", Arrays.asList("--input", "json"), "session",
            "json", "xml"
        );
        
        assertEquals("transform", inv.getToolName());
        assertEquals("json", inv.getInputType());
        assertEquals("xml", inv.getOutputType());
    }
    
    @Test
    void testGetToolReturnsSynthesizedTool() {
        ToolSynthesizer synthesizer = new ToolSynthesizer();
        
        List<ToolSynthesizer.ToolInvocation> invocations = Arrays.asList(
            new ToolSynthesizer.ToolInvocation("echo", Arrays.asList("hello"), "session")
        );
        
        ToolSynthesizer.ToolSequence sequence = new ToolSynthesizer.ToolSequence(invocations);
        ToolSynthesizer.SynthesizedTool tool = synthesizer.synthesizeFromSequence(sequence);
        
        ToolSynthesizer.SynthesizedTool retrieved = synthesizer.getTool(tool.getName());
        assertNotNull(retrieved);
        assertEquals(tool.getName(), retrieved.getName());
    }
    
    @Test
    void testMinSequenceFrequencyThreshold() {
        ToolSynthesizer synthesizer = new ToolSynthesizer();
        synthesizer.setMinSequenceFrequency(5);
        
        assertEquals(5, synthesizer.getMinSequenceFrequency());
    }
}