## Roadmap Implementation Prompt: Month 2 - Node Suggestion System in UI

### Assumptions
- We have PatternDetector identifying recurring sub-workflows
- We have NodeFactory generating specialized nodes from patterns
- We have a workflow template system
- The node suggestion system will recommend useful nodes/generated nodes to users
- Suggestions should appear in the node palette or context menu
- Initial implementation will focus on suggesting frequently used patterns

### Goal
Create a node suggestion system in the UI that:
1. Monitors user workflow creation patterns
2. Suggests relevant nodes based on detected patterns
3. Recommends generated nodes from NodeFactory when appropriate
4. Shows templates that match current workflow context
5. Learns from user acceptance/rejection of suggestions

### Success Criteria
- [ ] Suggestion system integrated with workflow canvas UI
- [ ] Method to analyze current workflow context for suggestions
- [ ] Method to display suggestions in node palette or context menu
- [ ] System tracks suggestion acceptance/rejection
- [ ] Unit tests verify suggestion logic
- [ ] Clear data flow from backend services to UI suggestions

### Implementation Plan
1. [Create UINodeSuggestionService class] → verify: service compiles
2. [Add method to analyze current workflow nodes/connections] → verify: understands workflow context
3. [Add method to get suggestions from PatternDetector/NodeFactory] → verify: retrieves relevant suggestions
4. [Add method to get relevant templates from template system] → verify: suggests appropriate templates
5. [Add UI integration points (palette/context menu)] → verify: suggestions appear in UI
6. [Add feedback tracking for suggestions] → verify: learns from user interactions
7. [Create unit tests with sample workflows] → verify: suggests appropriate nodes/templates
8. [Document suggestion ranking/scoring algorithm] → verify: clear explanation of suggestion logic

### Notes
- Start with simple heuristics (e.g., "if user has source+agent, suggest output")
- Focus on relevance over sophistication for initial version
- Assume we have access to current workflow state in the UI
- This completes Month 2's goals: dynamic node generation, template system, and suggestions
- Suggestions should feel helpful, not intrusive