package com.agent.orchestrator.model;

import java.util.List;

public enum ProjectType {
    FLUTTER("Flutter", List.of(".dart"),
            List.of("flutter", "build", "apk", "--debug"),
            List.of("dart", "analyze"),
            "pubspec.yaml"),

    PYTHON("Python", List.of(".py"),
            List.of("python3", "-m", "py_compile"),
            List.of("python3", "-m", "py_compile"),
            "requirements.txt"),

    WEB("Web (Vite/React)", List.of(".ts", ".tsx", ".js", ".jsx", ".css"),
            List.of("npm", "run", "build"),
            List.of("npx", "tsc", "--noEmit"),
            "package.json"),

    GO("Go", List.of(".go"),
            List.of("go", "build", "./..."),
            List.of("go", "vet", "./..."),
            "go.mod"),

    RUST("Rust", List.of(".rs"),
            List.of("cargo", "build"),
            List.of("cargo", "check"),
            "Cargo.toml");

    private final String displayName;
    private final List<String> extensions;
    private final List<String> buildCommand;
    private final List<String> validateCommand;
    private final String manifestFile;

    ProjectType(String displayName, List<String> extensions,
                List<String> buildCommand, List<String> validateCommand,
                String manifestFile) {
        this.displayName = displayName;
        this.extensions = extensions;
        this.buildCommand = buildCommand;
        this.validateCommand = validateCommand;
        this.manifestFile = manifestFile;
    }

    public String getDisplayName() { return displayName; }
    public List<String> getExtensions() { return extensions; }
    public List<String> getBuildCommand() { return buildCommand; }
    public List<String> getValidateCommand() { return validateCommand; }
    public String getManifestFile() { return manifestFile; }

    public boolean matchesExtension(String filePath) {
        return extensions.stream().anyMatch(filePath::endsWith);
    }

    public static ProjectType fromString(String s) {
        if (s == null || s.isBlank()) return FLUTTER;
        for (var t : values()) {
            if (t.name().equalsIgnoreCase(s)) return t;
        }
        return FLUTTER;
    }
}
