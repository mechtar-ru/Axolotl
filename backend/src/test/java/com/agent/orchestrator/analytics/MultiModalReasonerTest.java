package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MultiModalReasonerTest {
    
    @Test
    void testIdentifyTextModality() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        MultiModalReasoner.Modality modality = reasoner.identifyModality("Hello, this is plain text");
        assertEquals(MultiModalReasoner.Modality.TEXT, modality);
    }
    
    @Test
    void testIdentifyCodeModality() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        MultiModalReasoner.Modality modality = reasoner.identifyModality("function hello() { return true; }");
        assertEquals(MultiModalReasoner.Modality.CODE, modality);
    }
    
    @Test
    void testIdentifyCodeWithJavaKeyword() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        MultiModalReasoner.Modality modality = reasoner.identifyModality("public class Main { }");
        assertEquals(MultiModalReasoner.Modality.CODE, modality);
    }
    
    @Test
    void testIdentifyImageModality() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        MultiModalReasoner.Modality modality = reasoner.identifyModality("data:image/png;base64,abc123");
        assertEquals(MultiModalReasoner.Modality.IMAGE, modality);
    }
    
    @Test
    void testIdentifyAudioModality() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        MultiModalReasoner.Modality modality = reasoner.identifyModality("data:audio/mp3;base64,xyz");
        assertEquals(MultiModalReasoner.Modality.AUDIO, modality);
    }
    
    @Test
    void testIdentifyNullReturnsUnknown() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        MultiModalReasoner.Modality modality = reasoner.identifyModality(null);
        assertEquals(MultiModalReasoner.Modality.UNKNOWN, modality);
    }
    
    @Test
    void testAreModalitiesCompatible() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        assertTrue(reasoner.areModalitiesCompatible(
            MultiModalReasoner.Modality.TEXT, 
            MultiModalReasoner.Modality.CODE));
        assertTrue(reasoner.areModalitiesCompatible(
            MultiModalReasoner.Modality.CODE, 
            MultiModalReasoner.Modality.TEXT));
    }
    
    @Test
    void testAreModalitiesNotCompatible() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        assertFalse(reasoner.areModalitiesCompatible(
            MultiModalReasoner.Modality.IMAGE, 
            MultiModalReasoner.Modality.AUDIO));
        assertFalse(reasoner.areModalitiesCompatible(
            MultiModalReasoner.Modality.TEXT, 
            MultiModalReasoner.Modality.IMAGE));
    }
    
    @Test
    void testRouteDataSameModality() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        Object result = reasoner.routeData("hello", MultiModalReasoner.Modality.TEXT);
        assertEquals("hello", result);
    }
    
    @Test
    void testRouteDataConversion() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        Object result = reasoner.routeData("function test() {}", MultiModalReasoner.Modality.TEXT);
        assertNotNull(result);
    }
    
    @Test
    void testCreateUnifiedRepresentation() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        java.util.List<Object> data = java.util.List.of("plain text", "public void main()", "data:image/png;base64,abc");
        var unified = reasoner.createUnifiedRepresentation(data);
        
        assertTrue(unified.containsKey(MultiModalReasoner.Modality.TEXT));
        assertTrue(unified.containsKey(MultiModalReasoner.Modality.CODE));
        assertTrue(unified.containsKey(MultiModalReasoner.Modality.IMAGE));
    }
    
    @Test
    void testGetCompatibleModalities() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        var compatible = reasoner.getCompatibleModalities(MultiModalReasoner.Modality.TEXT);
        assertTrue(compatible.contains(MultiModalReasoner.Modality.TEXT));
    }
    
    @Test
    void testGetSupportedModalities() {
        MultiModalReasoner reasoner = new MultiModalReasoner();
        
        var modalities = reasoner.getSupportedModalities();
        assertTrue(modalities.size() >= 4);
    }
}