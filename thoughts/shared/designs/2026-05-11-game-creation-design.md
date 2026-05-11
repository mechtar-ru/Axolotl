# Game Creation Design for Axolotl

## Problem Statement
Axolotl currently lacks the ability to create games like Sokoban. While it has a robust app creation system with templates, schema generation, and workflow execution, it doesn't support game-specific app types, templates, or runtime components needed for game development.

## Constraints
1. Must integrate with existing app type system (CHAT, ANALYZER, GENERATOR, EMAIL, CUSTOM)
2. Must leverage existing schema generation and workflow execution systems
3. Must maintain backward compatibility with existing apps
4. Should follow existing patterns for template creation and UI components
5. Game creation should be accessible through the same UI pathways as other app types

## Approach
I will extend Axolotl's app creation system to support games by:
1. Adding a GAME app type to the existing app type enum
2. Creating game-specific templates (starting with a Sokoban template)
3. Adding game-specific runtime UI components
4. Ensuring the workflow execution engine can handle game logic through existing nodes
5. Following existing patterns for consistency

## Architecture

### 1. App Type Extension
Add GAME to the existing AppType enum in the backend model, and corresponding frontend types.

### 2. Template System
Create game-specific templates following the existing pattern:
- Backend template definitions in TemplateController
- Frontend template definitions in templates/index.ts
- External JSON templates in the templates/ directory

### 3. Runtime UI Components
Add a GameAppUI component similar to ChatAppUI and DocAnalyzerAppUI.

### 4. Workflow Execution
Leverage existing workflow execution system - games will be created as workflow schemas that can be executed to generate game code or run game logic.

## Components

### Backend Changes
1. **AppModel.java** - Add GAME to AppType enum
2. **TemplateController.java** - Add game templates (Sokoban, etc.)
3. **AppController.java** - Update starter templates to include GAME type
4. **SchemaService.java** - Ensure GAME app type is handled in schema generation

### Frontend Changes
1. **schemaStore.ts** - Update app type definitions
2. **templates/index.ts** - Add GAME app templates
3. **LiveView.vue** - Add routing for GAME app type to GameAppUI
4. **GameAppUI.vue** - New component for game runtime interface
5. **TemplateGallery.vue** - Will automatically show new GAME templates
6. **AppCard.vue** and **TemplateCard.vue** - Will show GAME icons automatically

### Template Examples
1. **Sokoban Template** - A workflow schema that:
   - Takes game parameters (grid size, level design)
   - Uses agent nodes to generate game logic
   - Uses SchemaBuilder nodes to create sub-systems
   - Outputs a playable game (HTML/JS or executable)

## Data Flow
1. User selects "Create New App" → chooses GAME type
2. User selects Sokoban template (or starts blank)
3. User fills in template variables (grid size, etc.)
4. System creates a WorkflowSchema with GAME app type
5. User can execute the schema to generate the game
6. Generated game can be played in the GameAppUI or exported

## Error Handling
Follow existing patterns:
- Schema validation errors during creation
- Execution errors handled by SchemaService and WebSocket
- Template variable validation

## Testing Strategy
1. Unit tests for new app type enum values
2. Integration tests for template loading
3. E2E tests for game creation flow
4. Manual testing of Sokoban template execution

## Open Questions
1. What specific game generation approach should we use? (HTML/JS, Python, etc.)
2. Should we create game-specific tools or reuse existing ones?
3. How complex should the initial Sokoban template be?