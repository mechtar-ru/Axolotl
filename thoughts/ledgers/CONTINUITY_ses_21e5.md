---
session: ses_21e5
updated: 2026-05-03T13:16:48.494Z
---



# Session Summary

## Goal
Implement `user_ask` tool properly following industry best practices - tool schema must be passed to LLM API so the model knows it can call this tool.

## Constraints & Preferences
- Follow Spring AI / LangGraph HITL patterns
- Tool definition must be sent to LLM in API request
- Must handle tool_calls loop (execute tool, inject result, continue)
- Do NOT change existing API surface

## Progress
### Done
- [x] Created `user_ask` tool handler in ToolExecutor.java (stores pending questions)
- [x] Added `/api/schemas/{id}/answer` endpoint in AgentController.java
- [x] Added frontend UI in ExecutionPanel.vue (shows input when waiting)
- [x] Tested schema creation - works, but no pending question detected
- [x] Researched industry best practices via DeepSeek (57 sources)
- [x] Identified CRITICAL GAP: tool schema NOT passed to LLM API

### In Progress
- [ ] Implement tool schema injection into LLM API calls
- [ ] Implement tool_calls detection and execution loop

### Blocked
- Deep task agent timed out attempting to implement the missing piece
- LlmProvider interface doesn't support tools parameter

## Key Decisions
- **Use standard function calling format**: Follow OpenAI spec with `tools` parameter containing `user_ask` function definition
- **Extend LlmProvider interface**: Add overloaded method to support tools parameter

## Next Steps
1. Extend `LlmProvider.java` interface with tools parameter support
2. Implement `tools` handling in all provider implementations (OpenAI, Anthropic, Ollama, etc.)
3. Update `SchemaService.java` to pass `user_ask` tool definition to LLM calls
4. Implement tool_calls detection loop - execute tool, inject result, continue
5. Test end-to-end flow

## Critical Context
The current implementation has the handler, storage, API endpoint, and UI - but the LLM never calls the tool because it doesn't know the tool exists. The model only sees the response format instruction, not the actual tool schema.

**Reference implementations:**
- https://spring.io/blog/2026/01/16/spring-ai-ask-user-question-tool
- https://docs.langchain.com/langgraph/concepts/human_in_the_loop

## File Operations
### Read
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/llm/LlmProvider.java` - interface with basic chat() method, NO tools support
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/llm/LlmService.java` - routing service (output truncated)
- `/Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/src/main/java/com/agent/orchestrator/service/ToolExecutor.java` - has user_ask handler at lines 143, 180, 209

### Modified (in previous sessions, now need updates)
- ToolExecutor.java - add enhanced user_ask with options support
- LlmProvider.java - add tools parameter
- SchemaService.java - pass tools to LLM, handle tool_calls loop
