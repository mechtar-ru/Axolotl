## Roadmap Implementation Prompt: Month 9 - Build preference-based workflow adaptation system

### Assumptions
- We have a PreferenceLearner that has learned individual user preferences
- We want to adapt workflows based on these preferences (e.g., suggest preferred nodes, pre-configure settings)
- Initial implementation will focus on using preferences to influence workflow generation and suggestions
- We'll assume we have access to the predicted preferences from PreferenceLearner

### Goal
Create a preference-based workflow adaptation system that:
1. Receives predicted preferences from PreferenceLearner for a given context
2. Adapts workflow suggestions, node generation, or configuration based on preferences
3. Can modify workflow templates or generated nodes to align with user preferences
4. Provides a way to override or adjust adaptations based on user feedback
5. Integrates with the workflow generation and suggestion systems

### Success Criteria
- [ ] PreferenceBasedAdapter class is created
- [ ] Method to receive preference predictions from PreferenceLearner
- [ ] Method to adapt workflow suggestions based on preferences
- [ ] Method to adapt node generation or configuration
- [ ] Unit tests verify adaptation logic
- [ ] Clear integration with PreferenceLearner and workflow systems

### Implementation Plan
1. [Create PreferenceBasedAdapter class] → verify: class compiles
2. [Add method updateFromPreferences(preferences)] → verify: stores current preferences
3. [Add method adaptSuggestions(baseSuggestions)] → verify: modifies suggestions based on preferences
4. [Add method adaptNodeGeneration(nodeSpec)] → verify: adjusts generated node to match preferences
5. [Add method adaptTemplate(template)] → verify: modifies workflow template based on preferences
6. [Add method to record user feedback on adaptations] → verify: allows learning from overrides
7. [Create unit tests with sample preferences and base suggestions] → verify: adaptations work correctly
8. [Document how this integrates with PreferenceLearner and suggestion systems] → verify: clear data flow
9. [Outline how user feedback is used to improve preference-based adaptations] → verify: clear loop

### Notes
- Start with simple adaptations: reordering suggestions to put preferred items first, setting default parameters
- Focus on correctness of adaptation logic
- Assume we have a way to get predicted preferences for the current context
- This implements the "Build preference-based workflow adaptation system" goal for Month 9