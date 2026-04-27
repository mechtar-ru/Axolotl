## Roadmap Implementation Prompt: Month 5 - DatasetBuilder for Training Data Generation

### Assumptions
- We have TrajectoryExtractor that extracts successful workflow execution trajectories
- We need to convert these trajectories into training data for fine-tuning models
- DatasetBuilder will handle formatting, filtering, and splitting of data
- Initial implementation will focus on creating (prompt, completion) pairs for LLM fine-tuning
- We'll assume we have a way to store and manage the dataset

### Goal
Create a DatasetBuilder class that:
1. Accepts trajectories from TrajectoryExtractor
2. Converts trajectories into training examples (e.g., prompt-completion pairs)
3. Applies formatting specific to the target model (e.g., tokenization, special tokens)
4. Filters out low-quality or redundant examples
5. Splits dataset into training, validation, and test sets
6. Outputs dataset in a format suitable for fine-tuning workflows

### Success Criteria
- [ ] DatasetBuilder class is created
- [ ] Method to add trajectories to the dataset
- [ ] Method to convert trajectories to training examples
- [ ] Method to apply model-specific formatting
- [ ] Method to filter and deduplicate examples
- [ ] Method to split dataset into train/val/test
- [ ] Unit tests verify dataset creation and formatting
- [ ] Clear integration with fine-tuning workflow

### Implementation Plan
1. [Create DatasetBuilder class] → verify: class compiles
2. [Add method addTrajectory(trajectory)] → verify: stores trajectory
3. [Add method toTrajectoryToExamples(trajectory)] → verify: converts to (prompt, completion)
4. [Add method applyFormatting(examples, modelType)] → verify: formats for target model
5. [Add method filterExamples(examples)] → verify: removes low-quality/duplicates
6. [Add method splitDataset(examples, trainRatio, valRatio)] → verify: splits correctly
7. [Add method getDataset(format)] → verify: returns dataset in requested format (e.g., JSONL)
8. [Create unit tests with sample trajectories] → verify: dataset built correctly
9. [Document how this integrates with automated fine-tuning workflow] → verify: clear data flow

### Notes
- Start with simple text-to-text formatting for LLMs
- Focus on correctness of example generation and splitting
- Assume we can store dataset in memory or disk
- This implements the "Create DatasetBuilder for training data generation" goal