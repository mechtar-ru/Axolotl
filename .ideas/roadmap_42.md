## Roadmap Implementation Prompt: Month 7 - Build workload complexity scoring system

### Assumptions
- We have CognitiveLoadEstimator that estimates cognitive load from interaction patterns.
- We want to quantify the complexity of the workflow itself (independent of user interaction) to understand how workflow complexity affects cognitive load.
- Initial implementation will focus on structural complexity metrics (similar to the workflow complexity metrics we built in Month 10, but now for the purpose of cognitive load estimation).
- We'll assume we have access to the workflow definition.

### Goal
Create a workflow complexity scoring system that:
1. Computes a complexity score for a given workflow based on structural properties (e.g., number of nodes, depth, branching factor, etc.)
2. Provides a normalized score (e.g., 0-1) that can be used by the CognitiveLoadEstimator
3. Can be tuned or weighted based on empirical data
4. Integrates with the CognitiveLoadEstimator to contribute to the overall cognitive load estimate

### Success Criteria
- [ ] WorkloadComplexityScorer class is created
- [ ] Method to compute complexity score from a workflow
- [ ] Method to normalize the score to a 0-1 range
- [ ] Unit tests verify scoring logic with sample workflows
- [ ] Clear integration with CognitiveLoadEstimator

### Implementation Plan
1. [Create WorkloadComplexityScorer class] → verify: class compiles
2. [Add method computeComplexity(workflow)] → verify: returns a raw complexity score
3. [Add method normalizeScore(rawScore)] → verify: returns a score between 0 and 1
4. [Choose a complexity formula (e.g., weighted sum of node count, depth, edge count)] → verify: formula defined
5. [Create unit tests with sample workflows] → verify: scores are computed and normalized correctly
6. [Document how this integrates with CognitiveLoadEstimator (e.g., as an input factor)] → verify: clear data flow
7. [Outline how to tune weights based on data] → verify: clear instructions

### Notes
- Start with a simple formula: complexity = (w1 * nodeCount + w2 * depth + w3 * edgeCount) normalized
- Focus on correctness of scoring and normalization
- Assume we can compute node count, depth, etc. from the workflow
- This implements the "Build workload complexity scoring system" goal for Month 7