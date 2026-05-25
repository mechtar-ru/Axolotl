## Roadmap Implementation Prompt: Month 8 - Natural Language Explanation Templates

### Assumptions
- We have an ExplanationGenerator that needs to produce natural language explanations
- We want to use templates to ensure explanations are consistent, readable, and cover necessary details
- Templates will have placeholders for dynamic information (e.g., model names, scores, workflow context)
- Initial implementation will focus on creating a set of templates for different decision types
- We'll assume we have a way to fill in the templates with data from the reasoning trace

### Goal
Create a template system for natural language explanations that:
1. Defines explanation templates for different decision types (model selection, routing, etc.)
2. Uses placeholders for dynamic data (e.g., {{model_name}}, {{success_rate}})
3. Allows for conditional formatting in templates (e.g., if confidence is low, add uncertainty warning)
4. Provides a way to select and fill a template based on the decision context
5. Integrates with the ExplanationGenerator to produce final explanations

### Success Criteria
- [ ] ExplanationTemplate class or system is created
- [ ] Set of templates for different decision types
- [ ] Method to render a template with provided data
- [ ] Method to select appropriate template based on decision context
- [ ] Unit tests verify template rendering and selection
- [ ] Clear integration with ExplanationGenerator and ReasoningTraceAnalyzer

### Implementation Plan
1. [Create ExplanationTemplate class] → verify: class compiles
2. [Add method to render template with data map] → verify: replaces placeholders correctly
3. [Create template for model selection] → verify: explains why a model was chosen
4. [Create template for workflow routing decision] → verify: explains why a path was taken
5. [Create template for node selection/generation] → verify: explains why a node was used or created
6. [Add conditional logic in templates (e.g., if confidence < threshold)] → verify: handles uncertainty
7. [Create unit tests with sample data and templates] → verify: renders correct explanations
8. [Document template syntax and available placeholders] → verify: clear for developers
9. [Outline how ExplanationGenerator will use this system] → verify: clear data flow

### Notes
- Start with simple string replacement templates (e.g., using Mustache or similar)
- Focus on correctness of rendering and template selection
- Assume we have the necessary data from the reasoning trace (model names, scores, etc.)
- This implements the "Build natural language explanation templates" goal for Month 8