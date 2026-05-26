## Roadmap Implementation Prompt: Month 3 - EnsembleModelRouter for Dynamic Model Selection

### Assumptions
- We have TaskType classification system that assigns task types to workflows
- We have ModelPreferenceTracker that tracks model performance per task type
- We need to route LLM calls to the best model for the given task type
- The router should consider preferences, but also allow fallbacks and overrides
- Initial implementation will be rule-based using the preference tracker

### Goal
Create an EnsembleModelRouter class that:
1. Receives a workflow and determines its task type (via TaskTypeClassifier)
2. Queries ModelPreferenceTracker for recommended models for that task type
3. Selects a model based on preferences (with optional exploration)
4. Provides an interface for the LLM service to get the model to use
5. Allows for manual model overrides if needed

### Success Criteria
- [ ] EnsembleModelRouter class is created
- [ ] Method to route a workflow execution to a model
- [ ] Uses TaskTypeClassifier to determine task type
- [ ] Uses ModelPreferenceTracker to get model recommendations
- [ ] Implements selection strategy (e.g., top model, or exploration)
- [ ] Unit tests verify correct routing based on preferences
- [ ] Clear integration point with LlmService

### Implementation Plan
1. [Create EnsembleModelRouter class] → verify: class compiles
2. [Add constructor dependencies (TaskTypeClassifier, ModelPreferenceTracker)] → verify: can be instantiated
3. [Add method getModelForWorkflow(workflow)] → verify: returns model ID
4. [Inside method: classify workflow using TaskTypeClassifier] → verify: gets task type
5. [Query ModelPreferenceTracker for top models for that task type] → verify: gets recommendations
6. [Apply selection strategy (e.g., use top model, or epsilon-greedy exploration)] → verify: selects a model
7. [Return selected model ID] → verify: correct format for LlmService
8. [Create unit tests with mocked classifiers and trackers] → verify: routing works as expected
9. [Document how LlmService will use this router] → verify: clear integration instructions

### Notes
- Start with simple selection (always top model); exploration can be added later
- Assume we have access to the workflow definition at routing time
- This implements the "Create EnsembleModelRouter for dynamic model selection" goal
- The router may later consider cost, latency, etc., but start with performance-based preference