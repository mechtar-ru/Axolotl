## Roadmap Implementation Prompt: Month 2 - Workflow Template System for Common Patterns

### Assumptions
- We have PatternDetector identifying recurring sub-workflows
- We have NodeFactory generating specialized nodes from patterns
- Workflow templates will provide reusable starting points for common workflow structures
- Templates should be editable and customizable by users
- Initial implementation will focus on saving and loading predefined workflow structures

### Goal
Create a workflow template system that:
1. Stores predefined workflow structures as templates
2. Allows users to create new workflows from templates
3. Supports saving user-created workflows as new templates
4. Provides categorization and tagging for template discovery
5. Integrates with the UI for template selection

### Success Criteria
- [ ] Template storage mechanism is created (file-based or database)
- [ ] Method to create workflow from template
- [ ] Method to save workflow as new template
- [ ] Template metadata (name, description, tags, category)
- [ ] Unit tests verify template creation and usage
- [ ] Clear integration points with workflow creation UI

### Implementation Plan
1. [Create WorkflowTemplate class/model] → verify: defines template structure
2. [Add template storage service] → verify: can save and retrieve templates
3. [Add method to instantiate workflow from template] → verify: creates editable copy
4. [Add method to save workflow as template] → verify: captures current workflow
5. [Add template metadata fields (name, description, tags)] → verify: enables organization
6. [Create unit tests with sample templates] → verify: templates work correctly
7. [Document template format and storage location] → verify: clear for developers
8. [Outline UI integration points] → verify: how templates appear in new workflow dialog

### Notes
- Start with simple file-based storage (JSON/YAML) in a templates directory
- Focus on correctness of template instantiation (deep copy)
- Assume we have a way to serialize/deserialize workflow definitions
- This feeds into Month 2's node suggestion system in UI
- Templates bridge the gap between detected patterns and user workflows