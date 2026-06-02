package com.agent.orchestrator.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DiffService {

    public String computeSimpleDiff(String oldText, String newText) {
        if (oldText.equals(newText)) return "(no changes)";
        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int maxContext = 3;

        int start = 0;
        while (start < oldLines.length && start < newLines.length
                && oldLines[start].equals(newLines[start])) {
            start++;
        }
        int oldEnd = oldLines.length - 1;
        int newEnd = newLines.length - 1;
        while (oldEnd >= start && newEnd >= start
                && oldLines[oldEnd].equals(newLines[newEnd])) {
            oldEnd--;
            newEnd--;
        }

        int ctxStart = Math.max(0, start - maxContext);
        for (int i = ctxStart; i < start; i++) {
            sb.append("  ").append(oldLines[i]).append("\n");
        }

        for (int i = start; i <= oldEnd; i++) {
            sb.append("- ").append(oldLines[i]).append("\n");
        }
        for (int i = start; i <= newEnd; i++) {
            sb.append("+ ").append(newLines[i]).append("\n");
        }

        int ctxEnd = Math.min(newLines.length - 1, oldEnd + maxContext);
        for (int i = Math.max(oldEnd, newEnd) + 1; i <= ctxEnd && i < newLines.length; i++) {
            sb.append("  ").append(newLines[i]).append("\n");
        }

        return sb.toString();
    }

    public List<Map<String, Object>> computeDiffPayloads(List<ExecutionStateManager.PendingDiff> pendingDiffs) {
        List<Map<String, Object>> diffPayloads = new ArrayList<>();
        for (ExecutionStateManager.PendingDiff pd : pendingDiffs) {
            Map<String, Object> d = new HashMap<>();
            d.put("filePath", pd.filePath);
            d.put("diff", computeSimpleDiff(pd.originalContent, pd.newContent));
            d.put("originalLength", pd.originalContent.length());
            d.put("newLength", pd.newContent.length());
            diffPayloads.add(d);
        }
        return diffPayloads;
    }
}
