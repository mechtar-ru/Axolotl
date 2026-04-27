## Roadmap Implementation Prompt: Month 7 - CognitiveLoadEstimator using Interaction Patterns

### Assumptions
- We can estimate cognitive load from user interaction patterns with the workflow canvas
- Interaction patterns include node creation/connection frequency, menu navigation, zoom/pan behavior, etc.
- Initial implementation will focus on measurable interaction metrics
- We'll assume we have access to user interaction events from the frontend

### Goal
Create a CognitiveLoadEstimator class that:
1. Collects user interaction data from workflow canvas usage
2. Estimates cognitive load based on interaction patterns (e.g., frequency of actions, hesitation, error rates)
3. Provides a cognitive load score (e.g., 0-1 scale)
4. Can be trained or calibrated to individual users
5. Integrates with the UI to provide real-time feedback

### Success Criteria
- [ ] CognitiveLoadEstimator class is created
- [ ] Method to record user interaction events
- [ ] Method to estimate cognitive load from interaction patterns
- [ ] Method to provide real-time cognitive load score
- [ ] Unit tests verify estimation logic with sample interaction data
- [ ] Clear integration points with frontend interaction tracking

### Implementation Plan
1. [Create CognitiveLoadEstimator class] → verify: class compiles
2. [Add method to record interaction event (type, timestamp, context)] → verify: stores interaction data
3. [Add method to compute cognitive load score] → verify: calculates score from recent interactions
4. [Add method to get current cognitive load level] → verify: returns normalized score
5. [Add method to reset/clear interaction history] → verify: allows fresh start
6. [Create unit tests with simulated interaction patterns] → verify: estimates load correctly
7. [Document which interaction patterns contribute to load estimation] → verify: clear explanation
8. [Outline how frontend will send interaction events] → verify: clear integration path

### Notes
- Start with simple heuristics: high frequency of undo/redo, repeated node deletions, long hesitation times
- Focus on correctness of load estimation formula
- Assume we can instrument the frontend to send interaction events to backend
- This implements the "Implement CognitiveLoadEstimator using interaction patterns" goal