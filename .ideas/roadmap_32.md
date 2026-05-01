## Roadmap Implementation Prompt: Month 9 - CollaborativeReasoningSpace for Shared Human-AI Workspaces

### Assumptions
- We want to support collaboration between humans and AI agents in a shared workspace
- The CollaborativeReasoningSpace will allow multiple users and AI agents to work on the same workflow
- Initial implementation will focus on real-time sharing of workflow state and basic conflict resolution
- We'll assume we have a way to broadcast changes and merge contributions

### Goal
Create a CollaborativeReasoningSpace class that:
1. Maintains a shared workflow state accessible to multiple users and AI agents
2. Broadcasts changes to all participants in real-time
3. Handles conflicts when multiple parties try to modify the same element
4. Provides awareness of who is viewing or editing which part of the workflow
5. Integrates with the UI to show cursors, selections, and edits from others
6. Supports both synchronous and asynchronous collaboration

### Success Criteria
- [ ] CollaborativeReasoningSpace class is created
- [ ] Method to join/leave the collaborative space
- [ ] Method to broadcast workflow changes to participants
- [ ] Method to receive and apply changes from others
- [ ] Conflict resolution strategy for concurrent modifications
- [ ] Unit tests verify basic collaboration and conflict handling
- [ ] Clear integration with WebSocket or real-time communication system

### Implementation Plan
1. [Create CollaborativeReasoningSpace class] → verify: class compiles
2. [Add method join(userId)] → verify: adds participant to space
3. [Add method leave(userId)] → verify: removes participant
4. [Add method applyChange(change, userId)] → verify: applies a change from a participant
5. [Add method broadcastChange(change)] → verify: sends change to all other participants
6. [Add conflict detection (e.g., two users trying to delete same node)] → verify: identifies conflicts
7. [Add conflict resolution (e.g., last-write-wins or merge)] → verify: resolves conflicts consistently
8. [Add method to get awareness info (who is viewing/editing what)] → verify: returns awareness data
9. [Create unit tests with simulated concurrent edits] → verify: collaboration works and conflicts resolved
10. [Document how this integrates with WebSocket execution handler or a dedicated collaboration service] → verify: clear integration path
11. [Outline how UI will use awareness data to show cursors and selections] → verify: clear for frontend

### Notes
- Start with simple text-based operational transformation or last-write-wins for conflicts
- Focus on correctness of change broadcasting and basic conflict resolution
- Assume we have a real-time communication mechanism (WebSocket) to send/receive changes
- This implements the "Create CollaborativeReasoningSpace for shared human-AI workspaces" goal for Month 9