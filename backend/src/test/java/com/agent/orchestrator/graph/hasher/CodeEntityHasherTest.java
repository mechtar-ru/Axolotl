package com.agent.orchestrator.graph.hasher;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeEntityHasherTest {

    private final CodeEntityHasher hasher = new CodeEntityHasher();
    private final JavaParser parser = new JavaParser();

    @Test
    void testClassHashStability_renameLocalVariable() throws Exception {
        String code1 = """
            package com.example;
            public class Calculator {
                public int add(int a, int b) {
                    int temp = a + b;
                    return temp;
                }
            }
            """;

        String code2 = """
            package com.example;
            public class Calculator {
                public int add(int a, int b) {
                    int result = a + b;
                    return result;
                }
            }
            """;

        CompilationUnit cu1 = parser.parse(code1).getResult().orElseThrow();
        CompilationUnit cu2 = parser.parse(code2).getResult().orElseThrow();

        var clazz1 = cu1.getClassByName("Calculator").orElseThrow();
        var clazz2 = cu2.getClassByName("Calculator").orElseThrow();

        String hash1 = hasher.hashClass(clazz1, "com.example");
        String hash2 = hasher.hashClass(clazz2, "com.example");

        assertEquals(hash1, hash2, "Hash should be stable when only local variable names change");
    }

    @Test
    void testClassHashChanges_onSignatureChange() throws Exception {
        String code1 = """
            package com.example;
            public class Calculator {
                public int add(int a, int b) { return a + b; }
            }
            """;

        String code2 = """
            package com.example;
            public class AdvancedCalculator extends Calculator {
                public int add(int a, int b) { return a + b; }
            }
            """;

        CompilationUnit cu1 = parser.parse(code1).getResult().orElseThrow();
        CompilationUnit cu2 = parser.parse(code2).getResult().orElseThrow();

        var clazz1 = cu1.getClassByName("Calculator").orElseThrow();
        var clazz2 = cu2.getClassByName("AdvancedCalculator").orElseThrow();

        String hash1 = hasher.hashClass(clazz1, "com.example");
        String hash2 = hasher.hashClass(clazz2, "com.example");

        assertNotEquals(hash1, hash2, "Hash should change when class signature changes");
    }

    @Test
    void testMethodHashStability_renameParameter() throws Exception {
        String code = """
            package com.example;
            public class Calculator {
                public int multiply(int x, int y) { return x * y; }
            }
            """;

        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        var clazz = cu.getClassByName("Calculator").orElseThrow();
        var method = clazz.getMethodsByName("multiply").get(0);

        String hash1 = hasher.hashMethod(method, "com.example.Calculator");

        assertNotNull(hash1);
        assertEquals(16, hash1.length(), "SHA-256 truncated to 16 chars");
    }

    @Test
    void testHashDifferentMethods() throws Exception {
        String code = """
            package com.example;
            public class Calculator {
                public int add(int a, int b) { return a + b; }
                public int subtract(int a, int b) { return a - b; }
            }
            """;

        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        var clazz = cu.getClassByName("Calculator").orElseThrow();

        var addMethod = clazz.getMethodsByName("add").get(0);
        var subMethod = clazz.getMethodsByName("subtract").get(0);

        String hashAdd = hasher.hashMethod(addMethod, "com.example.Calculator");
        String hashSub = hasher.hashMethod(subMethod, "com.example.Calculator");

        assertNotEquals(hashAdd, hashSub, "Different methods should have different hashes");
    }

    @Test
    void testHashIncludesModifiers() throws Exception {
        String code = """
            package com.example;
            public class Calculator {
                public static int add(int a, int b) { return a + b; }
                public int multiply(int a, int b) { return a * b; }
            }
            """;

        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        var clazz = cu.getClassByName("Calculator").orElseThrow();

        var staticMethod = clazz.getMethodsByName("add").get(0);
        var instanceMethod = clazz.getMethodsByName("multiply").get(0);

        String hashStatic = hasher.hashMethod(staticMethod, "com.example.Calculator");
        String hashInstance = hasher.hashMethod(instanceMethod, "com.example.Calculator");

        assertNotEquals(hashStatic, hashInstance, "Static vs instance should differ");
    }

    @Test
    void testHashFormat() {
        String hash = hasher.computeStableHash("class", "com.example.Test", "signature");
        assertNotNull(hash);
        assertEquals(16, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"), "Should be hex string");
    }
}