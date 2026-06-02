package com.agent.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class NodeFileWriter {

    private static final Logger log = LoggerFactory.getLogger(NodeFileWriter.class);

    @Value("${axolotl.sandbox.allowedWriteDirs:.}")
    private List<String> allowedWriteDirs;

    public String writeOutput(String outputType, String filePath, String fileFormat, String content) {
        if (content == null || content.isBlank()) {
            return "Нет данных для вывода";
        }
        if ("file".equals(outputType) && filePath != null && !filePath.isBlank()) {
            try {
                Path path = Path.of(filePath);
                Files.createDirectories(path.getParent());
                String dataToWrite = content;
                if ("json".equals(fileFormat)) {
                    dataToWrite = "{\n  \"result\": " + new ObjectMapper().writeValueAsString(content) + ",\n  \"timestamp\": " + System.currentTimeMillis() + "\n}";
                }
                Files.writeString(path, dataToWrite);
                return "Сохранено в файл: " + filePath;
            } catch (Exception e) {
                return "Ошибка записи файла: " + e.getMessage();
            }
        }
        return content;
    }

    public boolean isPathAllowed(String filePath) {
        if (allowedWriteDirs == null || allowedWriteDirs.isEmpty()) return true;
        try {
            Path resolved = Path.of(filePath).toAbsolutePath().normalize();
            for (String dir : allowedWriteDirs) {
                Path allowedBase = Path.of(dir).toAbsolutePath().normalize();
                if (resolved.startsWith(allowedBase)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
