## Roadmap Implementation Prompt: Month 4 - Evolutionary Algorithm for Workflow Optimization

### Assumptions
- We have a WorkflowMetaLearner and fitness functions for evaluating workflows
- We want to apply evolutionary algorithms to find optimal workflow structures
- The algorithm will maintain a population of workflow variants and evolve them over generations
- Selection, crossover, and mutation operators will be workflow-specific
- Initial implementation will focus on a simple genetic algorithm approach

### Goal
Create an evolutionary algorithm system for workflow optimization that:
1. Maintains a population of workflow variants
2. Selects workflows for reproduction based on fitness
3. Applies crossover (combining parts of two workflows)
4. Applies mutation (random changes to workflow structure)
5. Evolves the population over multiple generations
6. Returns the best workflow found

### Success Criteria
- [ ] WorkflowEvolutionaryOptimizer class is created
- [ ] Method to initialize population from a base workflow
- [ ] Fitness-based selection mechanism (e.g., tournament selection)
- [ ] Workflow-specific crossover operator
- [ ] Workflow-specific mutation operator
- [ ] Evolution loop over multiple generations
- [ ] Unit tests verify evolution can improve workflows
- [ ] Clear integration with WorkflowMetaLearner

### Implementation Plan
1. [Create WorkflowEvolutionaryOptimizer class] → verify: class compiles
2. [Add method to initialize population] → verify: creates variants of base workflow
3. [Add fitness-based selection method] → verify: selects better workflows more often
4. [Add workflow crossover method] → verify: combines parts of two parent workflows
5. [Add workflow mutation method] → verify: applies small random changes
6. [Add evolution loop (selection → crossover → mutation → replacement)] → verify: iterates generations
7. [Add method to get best workflow from population] → verify: returns highest fitness
8. [Create unit tests with simple workflow and fitness function] → verify: evolution finds improvement
9. [Document parameters (population size, mutation rate, etc.)] → verify: clear configuration

### Notes
- Start with simple mutations: add/remove nodes, change order of independent nodes
- Focus on correctness of evolutionary operators
- Assume we have ways to check if a workflow is valid (structural validity)
- This implements the "Build evolutionary algorithm for workflow optimization" goal
- Later phases can add more sophisticated operators and multi-objective optimization