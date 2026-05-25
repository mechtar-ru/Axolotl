## Roadmap Implementation Prompt: Month 5 - TrajectoryExtractor for Successful Workflow Patterns

### Assumptions
- We have workflow execution history with success/failure outcomes
- We want to extract trajectories (sequences of node executions) that led to success
- These trajectories will be used to generate training data for fine-tuning models
- Initial implementation will focus on extracting successful sequences of agent/node actions

### Goal
Create a TrajectoryExtractor class that:
1. Analyzes successful workflow executions
2. Extracts the sequence of actions/decisions made during execution
3. Records inputs, outputs, and intermediate states for each step
4. Outputs trajectories in a format suitable for training data generation
5. Can handle different types of nodes (source, agent, output, etc.)

### Success Criteria
- [ ] TrajectoryExtractor class is created
- [ ] Method to process a successful workflow execution
- [ ] Method to extract action sequences and associated data
- [ ] Output format includes state transitions and decisions
- [ ] Unit tests verify trajectory extraction accuracy
- [ ] Clear integration with DatasetBuilder for fine-tuning

### Implementation Plan
1. [Create TrajectoryExtractor class] → verify: class compiles
2. [Add method to accept execution history] → verify: can process workflow run
3. [Add method to extract node execution sequence] → verify: gets order of node activations
4. [For each node, capture input, configuration, and output] → verify: records relevant data
5. [Add method to format trajectory as training example] → verify: produces (prompt, completion) pairs
6. [Create unit tests with sample successful executions] → verify: extracts correct trajectories
7. [Document trajectory format for fine-tuning] → verify: clear specification
8. [Outline how this feeds into DatasetBuilder] → verify: clear data flow

### Notes
- Start with simple linear trajectories; branching/looping can be addressed later
- Focus on capturing the essential decision-making process
- Assume we have access to detailed execution logs (inputs, outputs, states)
- This implements the "Implement TrajectoryExtractor for successful workflow patterns" goal