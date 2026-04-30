# Axolotl Example Workflows

This folder contains example workflow schemas that demonstrate various capabilities of the Axolotl visual AI-agent orchestration app.

## Available Examples

### 01-hello-world.json
A simple workflow demonstrating the basic structure:
- Source node → Agent node → Output node
- Shows data flowing from input through processing to output
- Perfect for beginners to understand the basic concepts

### 02-data-pipeline.json
A data processing workflow showing:
- JSON data transformation and filtering
- Linear processing pipeline
- Demonstrates how agents can process structured data

### 03-ai-research-assistant.json
An advanced workflow featuring:
- Conditional logic (quality check with yes/no branches)
- Looping capability (retry research if quality is insufficient)
- Memory usage (storing research notes)
- Complex branching workflows
- Demonstrates sophisticated AI agent orchestration

## How to Use

1. Start the Axolotl application:
   ```bash
   # Start backend
   cd backend && mvn spring-boot:run
   
   # In another terminal, start frontend
   cd frontend && npm run dev
   ```

2. In the Axolotl UI:
   - Click the 📁 (Import) button in the sidebar
   - Select "Upload Schema" 
   - Choose one of the JSON files from this examples folder
   - The workflow will be loaded and displayed on the canvas
   - Click the ▶️ Execute button to run the workflow

3. To create your own workflows:
   - Use the toolbar to add nodes (+ button)
   - Connect nodes by dragging from output ports to input ports
   - Configure each node by double-clicking on it
   - Save your workflow using the 💾 button
   - Export workflows as JSON using the 📊 (Export) button

## Workflow Node Types

- **Source**: Input data for the workflow
- **Agent**: AI-powered processing node (uses LLMs)
- **Output**: Final result of the workflow
- **Condition**: Branching logic (yes/no outcomes)
- **Loop**: Repeating execution until condition met
- **Memory**: Storage for intermediate results
- And more...

## Tips

- Start with the hello-world example to understand the basics
- Try the data pipeline to see JSON processing in action
- Explore the research assistant for advanced features like branching and memory
- Use the execution panel (right side) to monitor progress in real-time
- Nodes can be grouped (Ctrl+G) for better organization
- Use keyboard shortcuts: Ctrl+S to save, Ctrl+Z to undo, Tab to navigate nodes