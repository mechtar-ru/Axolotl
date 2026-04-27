package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for CollaborativeReasoningSpace.
 */
class CollaborativeReasoningSpaceTest {

    @Test
    void shouldJoinSpace() {
        CollaborativeReasoningSpace space = new CollaborativeReasoningSpace("workspace-1");
        
        space.join("user-1", "Alice", false);
        
        assertEquals(1, space.getParticipantCount());
    }
    
    @Test
    void shouldLeaveSpace() {
        CollaborativeReasoningSpace space = new CollaborativeReasoningSpace("workspace-1");
        
        space.join("user-1", "Alice", false);
        space.leave("user-1");
        
        assertEquals(0, space.getParticipantCount());
    }
    
    @Test
    void shouldApplyChange() {
        CollaborativeReasoningSpace space = new CollaborativeReasoningSpace("workspace-1");
        
        space.join("user-1", "Alice", false);
        
        Map<String, Object> data = new HashMap<>();
        data.put("position", Arrays.asList(100, 200));
        
        CollaborativeReasoningSpace.WorkflowChange change = space.applyChange(
            "user-1", 
            CollaborativeReasoningSpace.ChangeType.NODE_ADD,
            "node-1",
            data
        );
        
        assertNotNull(change);
        assertEquals("node-1", change.getElementId());
    }
    
    @Test
    void shouldGetRecentChanges() {
        CollaborativeReasoningSpace space = new CollaborativeReasoningSpace("workspace-1");
        
        space.join("user-1", "Alice", false);
        
        Map<String, Object> data = new HashMap<>();
        space.applyChange("user-1", CollaborativeReasoningSpace.ChangeType.NODE_ADD, "node-1", data);
        
        List<CollaborativeReasoningSpace.WorkflowChange> recentChanges = space.getRecentChanges(10);
        
        assertEquals(1, recentChanges.size());
    }
    
    @Test
    void shouldGetAwarenessInfo() {
        CollaborativeReasoningSpace space = new CollaborativeReasoningSpace("workspace-1");
        
        space.join("user-1", "Alice", false);
        
        Set<String> viewing = new HashSet<>();
        viewing.add("node-1");
        space.updateViewing("user-1", viewing);
        
        Map<String, Object> awareness = space.getAwarenessInfo("user-1");
        
        assertNotNull(awareness);
        assertTrue(awareness.containsKey("localViewing"));
    }
    
    @Test
    void shouldReturnParticipants() {
        CollaborativeReasoningSpace space = new CollaborativeReasoningSpace("workspace-1");
        
        space.join("user-1", "Alice", false);
        space.join("user-2", "Bob", false);
        
        List<CollaborativeReasoningSpace.Participant> participants = space.getParticipants();
        
        assertEquals(2, participants.size());
    }
    
    @Test
    void shouldDetectConflict() {
        CollaborativeReasoningSpace space = new CollaborativeReasoningSpace("workspace-1",
            CollaborativeReasoningSpace.ConflictResolutionStrategy.LAST_WRITE_WINS);
        
        space.join("user-1", "Alice", false);
        space.join("user-2", "Bob", false);
        
        Map<String, Object> data = new HashMap<>();
        space.applyChange("user-1", CollaborativeReasoningSpace.ChangeType.NODE_UPDATE, "node-1", data);
        space.applyChange("user-2", CollaborativeReasoningSpace.ChangeType.NODE_UPDATE, "node-1", data);
        
        List<CollaborativeReasoningSpace.WorkflowChange> changes = space.getRecentChanges(5);
        
        assertEquals(2, changes.size());
    }
    
    @Test
    void shouldTrackVersion() {
        CollaborativeReasoningSpace space = new CollaborativeReasoningSpace("workspace-1");
        
        space.join("user-1", "Alice", false);
        
        Map<String, Object> data = new HashMap<>();
        space.applyChange("user-1", CollaborativeReasoningSpace.ChangeType.NODE_ADD, "node-1", data);
        
        long version = space.getCurrentVersion();
        
        assertTrue(version > 0);
    }
    
    @Test
    void shouldGetSpaceId() {
        CollaborativeReasoningSpace space = new CollaborativeReasoningSpace("workspace-1");
        
        assertEquals("workspace-1", space.getSpaceId());
    }
    
    @Test
    void shouldThrowWhenUserNotInSpace() {
        CollaborativeReasoningSpace space = new CollaborativeReasoningSpace("workspace-1");
        
        assertThrows(IllegalStateException.class, () -> {
            space.applyChange("user-1", CollaborativeReasoningSpace.ChangeType.NODE_ADD, "node-1", new HashMap<>());
        });
    }
}