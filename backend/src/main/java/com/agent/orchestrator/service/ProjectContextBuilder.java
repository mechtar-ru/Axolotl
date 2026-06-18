package com.agent.orchestrator.service;

import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.Plan;
import com.agent.orchestrator.model.Task;
import com.agent.orchestrator.model.TaskStatus;
import com.agent.orchestrator.repository.ExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ProjectContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProjectContextBuilder.class);
    private static final int MAX_CHARS = 8000;
    private static final int MAX_SESSION_HISTORY = 5;

    private final PlanService planService;
    private final ExecutionRepository executionRepository;

    public ProjectContextBuilder(PlanService planService,
                                  ExecutionRepository executionRepository) {
        this.planService = planService;
        this.executionRepository = executionRepository;
    }

    /**
     * Build a context string describing the current project state.
     * Includes file tree, session history from the plan, session goal, and past run results.
     */
    public String buildContext(String targetPath, String workspaceId) {
        return buildContext(targetPath, workspaceId, null);
    }

    /**
     * Build context with optional schemaId for fetching past execution run results.
     */
    public String buildContext(String targetPath, String workspaceId, String schemaId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current project state (target: ").append(targetPath).append("):\n\n");

        // Session context
        if (schemaId != null && !schemaId.isBlank()) {
            try {
                List<ExecutionRun> completedRuns = executionRepository.getCompletedRuns(schemaId, MAX_SESSION_HISTORY);
                int sessionNumber = completedRuns.size() + 1;  // 1-indexed: first run = session 1

                sb.append("\n## Session Context\n");
                sb.append("This is **Session ").append(sessionNumber);
                if (sessionNumber > 1) {
                    sb.append("**, continuing from ").append(completedRuns.size())
                      .append(" previous session(s)");
                }
                sb.append(".\n");

                if (sessionNumber > 1) {
                    sb.append("\n### Previous sessions:\n");
                    for (int i = 0; i < completedRuns.size(); i++) {
                        ExecutionRun run = completedRuns.get(i);
                        int sNum = i + 1;
                        sb.append("- **Session ").append(sNum).append("**: ");
                        sb.append("status=").append(run.getStatus());
                        if (run.getError() != null && !run.getError().isBlank()) {
                            sb.append(", error=").append(run.getError().length() > 80
                                    ? run.getError().substring(0, 80) + "..."
                                    : run.getError());
                        }
                        if (run.getGeneratedFiles() != null && !run.getGeneratedFiles().isEmpty()) {
                            sb.append(", files: ").append(String.join(", ", run.getGeneratedFiles()));
                        }
                        if (run.getTotalTokens() > 0) {
                            sb.append(", tokens=").append(run.getTotalTokens());
                        }
                        sb.append("\n");
                    }
                    sb.append("\n**IMPORTANT**: You are continuing development. Read existing files with `directory_read` and `file_read` before creating new ones. Do NOT recreate what already exists — extend and modify.\n");
                }
            } catch (Exception e) {
                log.warn("Failed to read session context: {}", e.getMessage(), e);
            }
        }

        // File tree
        Path targetDir = Path.of(targetPath);
        if (Files.exists(targetDir)) {
            sb.append("\n### File Tree\n");
            appendFileTree(sb, targetDir, "");
        } else {
            sb.append("\n(Directory does not exist yet — this is a fresh project)\n");
        }

        // Session goal
        try {
            String sessionGoal = planService.getSessionGoal(workspaceId);
            if (sessionGoal != null && !sessionGoal.isBlank()) {
                sb.append("\n## This Session Goal\n\n").append(sessionGoal).append("\n");
            }
        } catch (Exception e) {
            log.warn("Failed to read session goal: {}", e.getMessage(), e);
        }

        // Session history (completed tasks from plan)
        try {
            Plan plan = planService.getPlan(workspaceId);
            List<Task> completedTasks = plan.getTasks().stream()
                    .filter(t -> t.getStatus() == TaskStatus.DONE)
                    .toList();
            if (!completedTasks.isEmpty()) {
                sb.append("\nPrevious sessions completed:\n");
                for (int i = 0; i < completedTasks.size(); i++) {
                    Task t = completedTasks.get(i);
                    sb.append("  [").append(i + 1).append("] \"").append(t.getTitle()).append("\"");
                    if (t.getGeneratedFiles() != null && !t.getGeneratedFiles().isEmpty()) {
                        sb.append(" → ");
                        sb.append(t.getGeneratedFiles().stream()
                                .map(Task.GeneratedFile::getPath)
                                .collect(Collectors.joining(", ")));
                    }
                    sb.append("\n");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read plan history: {}", e.getMessage(), e);
        }

        // Truncate if too long (>2000 tokens ≈ 8000 chars)
        if (sb.length() > MAX_CHARS) {
            sb.setLength(MAX_CHARS);
            sb.append("\n[... truncated]");
        }

        return sb.toString();
    }

    private void appendFileTree(StringBuilder sb, Path dir, String prefix) {
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> sorted = entries.sorted().toList();
            for (int i = 0; i < sorted.size(); i++) {
                Path entry = sorted.get(i);
                boolean isLast = (i == sorted.size() - 1);
                String connector = isLast ? "\u2514\u2500\u2500 " : "\u251C\u2500\u2500 ";
                String nextPrefix = isLast ? prefix + "    " : prefix + "\u2502   ";

                if (Files.isDirectory(entry)) {
                    sb.append(prefix).append(connector).append(entry.getFileName()).append("/\n");
                    // Limit recursion depth to 3
                    if (prefix.length() < 12) {
                        appendFileTree(sb, entry, nextPrefix);
                    } else {
                        sb.append(nextPrefix).append("...\n");
                    }
                } else {
                    sb.append(prefix).append(connector).append(entry.getFileName()).append("\n");
                }
            }
        } catch (IOException e) {
            sb.append(prefix).append("(error reading directory: ").append(e.getMessage()).append(")\n");
        }
    }
}
