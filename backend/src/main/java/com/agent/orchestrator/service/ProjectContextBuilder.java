package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Plan;
import com.agent.orchestrator.model.Task;
import com.agent.orchestrator.model.TaskStatus;
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
    private static final int MAX_CHARS = 4000;

    private final PlanService planService;

    public ProjectContextBuilder(PlanService planService) {
        this.planService = planService;
    }

    /**
     * Build a context string describing the current project state.
     * Includes file tree and session history from the plan.
     */
    public String buildContext(String targetPath, String workspaceId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current project state (target: ").append(targetPath).append("):\n\n");

        // File tree
        Path targetDir = Path.of(targetPath);
        if (Files.exists(targetDir)) {
            appendFileTree(sb, targetDir, "");
        } else {
            sb.append("(directory does not exist yet)\n");
        }

        // Session history
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
            log.warn("Failed to read plan history: {}", e.getMessage());
        }

        // Truncate if too long (>1000 tokens ≈ 4000 chars)
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
                String connector = isLast ? "└── " : "├── ";
                String nextPrefix = isLast ? prefix + "    " : prefix + "│   ";

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
