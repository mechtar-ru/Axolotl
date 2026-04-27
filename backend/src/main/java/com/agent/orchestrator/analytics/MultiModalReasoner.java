package com.agent.orchestrator.analytics;

import java.util.*;

/**
 * Reasoner for handling multiple modalities (text, code, image, audio) in workflows.
 * 
 * Integration points:
 * - identifyModality(data) - determines input modality from data
 * - areModalitiesCompatible(modA, modB) - checks if two modalities can connect
 * - routeData(data, targetModality) - converts or routes data to target modality
 * - createUnifiedRepresentation(dataList) - combines multimodal data into unified form
 * 
 * Usage in workflows: Use with node types that support different modalities,
 * routing data to appropriate processing nodes based on modality compatibility.
 */


public class MultiModalReasoner {
    
    public enum Modality {
        TEXT, CODE, IMAGE, AUDIO, UNKNOWN
    }
    
    private static final Map<Modality, Set<Modality>> COMPATIBILITY_MAP = new HashMap<>();
    
    static {
        COMPATIBILITY_MAP.put(Modality.TEXT, Set.of(Modality.TEXT, Modality.CODE));
        COMPATIBILITY_MAP.put(Modality.CODE, Set.of(Modality.CODE, Modality.TEXT));
        COMPATIBILITY_MAP.put(Modality.IMAGE, Set.of(Modality.IMAGE));
        COMPATIBILITY_MAP.put(Modality.AUDIO, Set.of(Modality.AUDIO));
        COMPATIBILITY_MAP.put(Modality.UNKNOWN, Set.of(Modality.UNKNOWN));
    }
    
    /**
     * Identifies the modality of input data.
     */
    public Modality identifyModality(Object data) {
        if (data == null) {
            return Modality.UNKNOWN;
        }
        
        String dataStr = data.toString().toLowerCase();
        
        // Code detection - starts with common code patterns
        if (dataStr.contains("function ") || dataStr.contains("def ") || 
            dataStr.contains("class ") || dataStr.contains("public ") ||
            dataStr.contains("import ") || dataStr.contains("package ") ||
            dataStr.contains("{") && dataStr.contains("}")) {
            return Modality.CODE;
        }
        
        // Image detection - image file extensions or base64 image headers
        if (dataStr.startsWith("data:image/") || dataStr.contains(".png") || 
            dataStr.contains(".jpg") || dataStr.contains(".jpeg") ||
            dataStr.startsWith("/9j/")) {
            return Modality.IMAGE;
        }
        
        // Audio detection - audio file extensions or base64 audio headers
        if (dataStr.startsWith("data:audio/") || dataStr.contains(".mp3") ||
            dataStr.contains(".wav") || dataStr.contains(".ogg")) {
            return Modality.AUDIO;
        }
        
        // Text detection - plain text content
        if (!dataStr.isEmpty() && dataStr.length() < 10000) {
            return Modality.TEXT;
        }
        
        return Modality.UNKNOWN;
    }
    
    /**
     * Checks if two modalities are compatible for connection.
     */
    public boolean areModalitiesCompatible(Modality modA, Modality modB) {
        if (modA == null || modB == null) {
            return false;
        }
        
        Set<Modality> compatibleWithA = COMPATIBILITY_MAP.get(modA);
        if (compatibleWithA == null) {
            return false;
        }
        
        return compatibleWithA.contains(modB);
    }
    
    /**
     * Routes data to target modality if conversion is possible.
     * Returns the routed data or null if conversion not possible.
     */
    public Object routeData(Object data, Modality targetModality) {
        Modality sourceModality = identifyModality(data);
        
        if (sourceModality == targetModality) {
            return data;
        }
        
        // Simple conversions
        if (sourceModality == Modality.CODE && targetModality == Modality.TEXT) {
            return data.toString();
        }
        
        if (sourceModality == Modality.TEXT && targetModality == Modality.CODE) {
            return data.toString();
        }
        
        // Cannot convert between media types without external tools
        return null;
    }
    
    /**
     * Creates a unified representation of multimodal data.
     * Returns a map with modality as key and data as value.
     */
    public Map<Modality, Object> createUnifiedRepresentation(List<Object> dataList) {
        Map<Modality, Object> unified = new LinkedHashMap<>();
        
        for (Object data : dataList) {
            Modality modality = identifyModality(data);
            if (modality != Modality.UNKNOWN) {
                unified.put(modality, data);
            }
        }
        
        return unified;
    }
    
    /**
     * Gets list of supported modalities.
     */
    public List<Modality> getSupportedModalities() {
        return Arrays.asList(Modality.values());
    }
    
    /**
     * Gets modalities compatible with given modality.
     */
    public List<Modality> getCompatibleModalities(Modality modality) {
        Set<Modality> compatible = COMPATIBILITY_MAP.get(modality);
        if (compatible == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(compatible);
    }
}