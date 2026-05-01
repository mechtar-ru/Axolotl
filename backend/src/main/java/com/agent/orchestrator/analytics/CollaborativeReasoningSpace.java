package com.agent.orchestrator.analytics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Supports collaborative workspaces for humans and AI agents working on the same workflow.
 * 
 * Integration Points:
 * - WebSocket execution handler: broadcast/receive workflow changes
 * - WorkflowCanvas: apply changes from other participants
 * - UI: show cursors, selections, and edits from others
 * - Multiple users/agents: can join and collaborate in real-time
 * 
 * Features:
 * - Join/leave collaborative space
 * - Broadcast changes to all participants
 * - Conflict detection for concurrent modifications
 * - Conflict resolution (last-write-wins by default)
 * - Awareness information (who is viewing/editing what)
 */


public class CollaborativeReasoningSpace {
    
    public enum ChangeType {
        NODE_ADD,
        NODE_REMOVE,
        NODE_UPDATE,
        EDGE_ADD,
        EDGE_REMOVE,
        POSITION_CHANGE,
        WORKFLOW_UPDATE,
        SELECTION_CHANGE
    }
    
    public static class Participant {
        private final String userId;
        private final String name;
        private final boolean isAiAgent;
        private final Set<String> viewingElements;
        private final Set<String> editingElements;
        private final long joinedAt;
        
        public Participant(String userId, String name, boolean isAiAgent) {
            this.userId = userId;
            this.name = name;
            this.isAiAgent = isAiAgent;
            this.viewingElements = ConcurrentHashMap.newKeySet();
            this.editingElements = ConcurrentHashMap.newKeySet();
            this.joinedAt = System.currentTimeMillis();
        }
        
        public String getUserId() { return userId; }
        public String getName() { return name; }
        public boolean isAiAgent() { return isAiAgent; }
        public Set<String> getViewingElements() { return viewingElements; }
        public Set<String> getEditingElements() { return editingElements; }
        public long getJoinedAt() { return joinedAt; }
    }
    
    public static class WorkflowChange {
        private final String changeId;
        private final String userId;
        private final ChangeType type;
        private final String elementId;
        private final Map<String, Object> data;
        private final long timestamp;
        private final long version;
        
        public WorkflowChange(String changeId, String userId, ChangeType type, 
                         String elementId, Map<String, Object> data, long version) {
            this.changeId = changeId;
            this.userId = userId;
            this.type = type;
            this.elementId = elementId;
            this.data = data != null ? data : new HashMap<>();
            this.timestamp = System.currentTimeMillis();
            this.version = version;
        }
        
        public String getChangeId() { return changeId; }
        public String getUserId() { return userId; }
        public ChangeType getType() { return type; }
        public String getElementId() { return elementId; }
        public Map<String, Object> getData() { return data; }
        public long getTimestamp() { return timestamp; }
        public long getVersion() { return version; }
    }
    
    public static class ConflictInfo {
        private final WorkflowChange firstChange;
        private final WorkflowChange secondChange;
        private final String elementId;
        private final String resolution;
        
        public ConflictInfo(WorkflowChange firstChange, WorkflowChange secondChange,
                         String elementId, String resolution) {
            this.firstChange = firstChange;
            this.secondChange = secondChange;
            this.elementId = elementId;
            this.resolution = resolution;
        }
        
        public WorkflowChange getFirstChange() { return firstChange; }
        public WorkflowChange getSecondChange() { return secondChange; }
        public String getElementId() { return elementId; }
        public String getResolution() { return resolution; }
    }
    
    public interface ChangeListener {
        void onChange(WorkflowChange change);
        void onConflict(ConflictInfo conflict);
        void onParticipantJoined(Participant participant);
        void onParticipantLeft(Participant participant);

        static ChangeListener noOp() {
            return new ChangeListener() {
                public void onChange(WorkflowChange change) {}
                public void onConflict(ConflictInfo conflict) {}
                public void onParticipantJoined(Participant participant) {}
                public void onParticipantLeft(Participant participant) {}
            };
        }
    }
    
    private final String spaceId;
    private final ConcurrentHashMap<String, Participant> participants;
    private final ConcurrentLinkedQueue<WorkflowChange> changeHistory;
    private final ConcurrentHashMap<String, Long> elementVersions;
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<ChangeListener>> listeners;
    private final ConflictResolutionStrategy resolutionStrategy;
    private long lastVersion;
    
    public enum ConflictResolutionStrategy {
        LAST_WRITE_WINS,
        MERGE,
        FIRST_WRITE_WINS
    }
    
    public CollaborativeReasoningSpace() {
        this(UUID.randomUUID().toString(), ConflictResolutionStrategy.LAST_WRITE_WINS);
    }
    
    public CollaborativeReasoningSpace(String spaceId) {
        this(spaceId, ConflictResolutionStrategy.LAST_WRITE_WINS);
    }
    
    public CollaborativeReasoningSpace(String spaceId, ConflictResolutionStrategy resolutionStrategy) {
        this.spaceId = spaceId;
        this.participants = new ConcurrentHashMap<>();
        this.changeHistory = new ConcurrentLinkedQueue<>();
        this.elementVersions = new ConcurrentHashMap<>();
        this.listeners = new ConcurrentHashMap<>();
        this.resolutionStrategy = resolutionStrategy;
        this.lastVersion = 0;
    }
    
    /**
     * Adds a participant to the collaborative space.
     */
    public void join(String userId, String name, boolean isAiAgent) {
        Participant participant = new Participant(userId, name, isAiAgent);
        participants.put(userId, participant);
        notifyParticipantJoined(participant);
    }
    
    /**
     * Removes a participant from the space.
     */
    public void leave(String userId) {
        Participant participant = participants.remove(userId);
        if (participant != null) {
            notifyParticipantLeft(participant);
        }
    }
    
    /**
     * Applies a change from a participant.
     */
    public WorkflowChange applyChange(String userId, ChangeType type, String elementId, Map<String, Object> data) {
        if (!participants.containsKey(userId)) {
            throw new IllegalStateException("User not in space: " + userId);
        }
        
        Long existingVersion = elementVersions.get(elementId);
        long expectedVersion = (existingVersion != null) ? existingVersion : lastVersion;
        
        WorkflowChange change = detectAndResolveConflict(userId, type, elementId, data, expectedVersion);
        
        changeHistory.add(change);
        elementVersions.put(elementId, change.getVersion());
        
        if (change.getVersion() > lastVersion) {
            lastVersion = change.getVersion();
        }
        
        notifyChange(change);
        
        return change;
    }
    
    /**
     * Detects and resolves conflicts when applying changes.
     */
    private WorkflowChange detectAndResolveConflict(String userId, ChangeType type, 
                                                  String elementId, Map<String, Object> data,
                                                  long expectedVersion) {
        Long elementVersion = elementVersions.get(elementId);
        
        if (elementVersion != null && elementVersion >= expectedVersion) {
            ConflictInfo conflict = detectConflict(userId, type, elementId, elementVersion);
            if (conflict != null) {
                resolveConflict(conflict);
            }
        }
        
        return new WorkflowChange(
            UUID.randomUUID().toString(),
            userId,
            type,
            elementId,
            data,
            expectedVersion + 1
        );
    }
    
    /**
     * Detects if there's a conflict for an element.
     */
    private ConflictInfo detectConflict(String userId, ChangeType type, 
                                        String elementId, long elementVersion) {
        List<WorkflowChange> recentChanges = getRecentChanges(10);
        
        WorkflowChange lastChange = null;
        for (WorkflowChange change : recentChanges) {
            if (change.getElementId().equals(elementId)) {
                lastChange = change;
                break;
            }
        }
        
        if (lastChange != null && !lastChange.getUserId().equals(userId)) {
            return new ConflictInfo(
                lastChange, 
                null,
                elementId,
                "conflict detected"
            );
        }
        
        return null;
    }
    
    /**
     * Resolves a conflict.
     */
    private void resolveConflict(ConflictInfo conflict) {
        if (resolutionStrategy == ConflictResolutionStrategy.LAST_WRITE_WINS) {
            // Last write wins - allow the new change to override
        } else if (resolutionStrategy == ConflictResolutionStrategy.FIRST_WRITE_WINS) {
            // First write wins - keep the old version
        }
        notifyConflict(conflict);
    }
    
    /**
     * Broadcasts a change to all participants.
     */
    public void broadcastChange(WorkflowChange change) {
        for (Map.Entry<String, ConcurrentLinkedQueue<ChangeListener>> entry : listeners.entrySet()) {
            for (ChangeListener l : entry.getValue()) {
                l.onChange(change);
            }
        }
    }
    
    /**
     * Gets recent changes.
     */
    public List<WorkflowChange> getRecentChanges(int limit) {
        return changeHistory.stream()
            .skip(Math.max(0, changeHistory.size() - limit))
            .collect(Collectors.toList());
    }
    
    /**
     * Gets all changes since a version.
     */
    public List<WorkflowChange> getChangesSince(long version) {
        return changeHistory.stream()
            .filter(c -> c.getVersion() > version)
            .collect(Collectors.toList());
    }
    
    /**
     * Gets awareness information for a participant.
     */
    public Map<String, Object> getAwarenessInfo(String userId) {
        Participant participant = participants.get(userId);
        if (participant == null) {
            return Collections.emptyMap();
        }
        
        Map<String, Object> awareness = new HashMap<>();
        
        List<Map<String, Object>> otherParticipants = new ArrayList<>();
        for (Participant p : participants.values()) {
            if (!p.getUserId().equals(userId)) {
                Map<String, Object> info = new HashMap<>();
                info.put("userId", p.getUserId());
                info.put("name", p.getName());
                info.put("isAiAgent", p.isAiAgent());
                info.put("viewing", new ArrayList<>(p.getViewingElements()));
                info.put("editing", new ArrayList<>(p.getEditingElements()));
                otherParticipants.add(info);
            }
        }
        
        awareness.put("participants", otherParticipants);
        awareness.put("localViewing", new ArrayList<>(participant.getViewingElements()));
        awareness.put("localEditing", new ArrayList<>(participant.getEditingElements()));
        
        return awareness;
    }
    
    /**
     * Updates what a participant is viewing.
     */
    public void updateViewing(String userId, Set<String> elementIds) {
        Participant participant = participants.get(userId);
        if (participant != null) {
            participant.getViewingElements().clear();
            participant.getViewingElements().addAll(elementIds);
        }
    }
    
    /**
     * Updates what a participant is editing.
     */
    public void updateEditing(String userId, Set<String> elementIds) {
        Participant participant = participants.get(userId);
        if (participant != null) {
            participant.getEditingElements().clear();
            participant.getEditingElements().addAll(elementIds);
        }
    }
    
    /**
     * Gets all participants in the space.
     */
    public List<Participant> getParticipants() {
        return new ArrayList<>(participants.values());
    }
    
    /**
     * Gets participant count.
     */
    public int getParticipantCount() {
        return participants.size();
    }
    
    /**
     * Gets space ID.
     */
    public String getSpaceId() {
        return spaceId;
    }
    
    /**
     * Gets current version.
     */
    public long getCurrentVersion() {
        return lastVersion;
    }
    
    /**
     * Adds a change listener.
     */
    public void addListener(String userId, ChangeListener listener) {
        listeners.computeIfAbsent(userId, k -> new ConcurrentLinkedQueue<>()).add(listener);
    }
    
    /**
     * Removes a change listener.
     */
    public void removeListener(String userId, ChangeListener listener) {
        ConcurrentLinkedQueue<ChangeListener> userListeners = listeners.get(userId);
        if (userListeners != null) {
            userListeners.remove(listener);
        }
    }
    
    private void notifyChange(WorkflowChange change) {
        for (ConcurrentLinkedQueue<ChangeListener> queue : listeners.values()) {
            for (ChangeListener l : queue) {
                l.onChange(change);
            }
        }
    }
    
    private void notifyConflict(ConflictInfo conflict) {
        for (ConcurrentLinkedQueue<ChangeListener> queue : listeners.values()) {
            for (ChangeListener l : queue) {
                l.onConflict(conflict);
            }
        }
    }
    
    private void notifyParticipantJoined(Participant participant) {
        for (ConcurrentLinkedQueue<ChangeListener> queue : listeners.values()) {
            for (ChangeListener l : queue) {
                l.onParticipantJoined(participant);
            }
        }
    }
    
    private void notifyParticipantLeft(Participant participant) {
        for (ConcurrentLinkedQueue<ChangeListener> queue : listeners.values()) {
            for (ChangeListener l : queue) {
                l.onParticipantLeft(participant);
            }
        }
    }
}