package com.agent.orchestrator.graph.loader;

import com.agent.orchestrator.graph.model.CodeClass;
import com.agent.orchestrator.graph.model.CodeField;
import com.agent.orchestrator.graph.model.CodeMethod;
import com.agent.orchestrator.graph.repository.CodeClassRepository;
import com.agent.orchestrator.graph.repository.CodeFieldRepository;
import com.agent.orchestrator.graph.repository.CodeMethodRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AstDependencyResolverTest {

    @Mock
    private CodeClassRepository classRepo;
    @Mock
    private CodeMethodRepository methodRepo;
    @Mock
    private CodeFieldRepository fieldRepo;

    @Test
    void testResolveExtendsDependency() {
        CodeClass classA = new CodeClass("A", "com.example.A", "com.example");
        classA.setAstBody("""
            package com.example;
            public class A {}
            """);

        CodeClass classB = new CodeClass("B", "com.example.B", "com.example");
        classB.setAstBody("""
            package com.example;
            import com.example.A;
            public class B extends A {}
            """);

        when(classRepo.findAll()).thenReturn(List.of(classA, classB));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(classB);

        assertTrue(deps.contains("com.example.A"), "B should depend on A via extends");
    }

    @Test
    void testResolveImplementsDependency() {
        CodeClass interfaceI = new CodeClass("MyInterface", "com.example.MyInterface", "com.example");
        interfaceI.setAstBody("""
            package com.example;
            public interface MyInterface {}
            """);

        CodeClass classC = new CodeClass("C", "com.example.C", "com.example");
        classC.setAstBody("""
            package com.example;
            import com.example.MyInterface;
            public class C implements MyInterface {}
            """);

        when(classRepo.findAll()).thenReturn(List.of(interfaceI, classC));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(classC);

        assertTrue(deps.contains("com.example.MyInterface"), "C should depend on MyInterface via implements");
    }

    @Test
    void testResolveFieldTypeDependency() {
        CodeClass user = new CodeClass("User", "com.example.User", "com.example");
        user.setAstBody("""
            package com.example;
            public class User {}
            """);

        CodeClass service = new CodeClass("Service", "com.example.Service", "com.example");
        service.setAstBody("""
            package com.example;
            import com.example.User;
            public class Service {
                private User user;
            }
            """);

        when(classRepo.findAll()).thenReturn(List.of(user, service));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(service);

        assertTrue(deps.contains("com.example.User"), "Service should depend on User via field type");
    }

    @Test
    void testResolveMethodParamDependency() {
        CodeClass dto = new CodeClass("UserDTO", "com.example.UserDTO", "com.example");
        dto.setAstBody("package com.example;\npublic class UserDTO {}");

        CodeClass controller = new CodeClass("Controller", "com.example.Controller", "com.example");
        controller.setAstBody("""
            package com.example;
            import com.example.UserDTO;
            public class Controller {
                public void handle(UserDTO dto) {}
            }
            """);

        when(classRepo.findAll()).thenReturn(List.of(dto, controller));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(controller);

        assertTrue(deps.contains("com.example.UserDTO"), "Controller should depend on UserDTO via method param");
    }

    @Test
    void testResolveMethodReturnTypeDependency() {
        CodeClass result = new CodeClass("Result", "com.example.Result", "com.example");
        result.setAstBody("package com.example;\npublic class Result {}");

        CodeClass factory = new CodeClass("Factory", "com.example.Factory", "com.example");
        factory.setAstBody("""
            package com.example;
            import com.example.Result;
            public class Factory {
                public Result create() { return new Result(); }
            }
            """);

        when(classRepo.findAll()).thenReturn(List.of(result, factory));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(factory);

        assertTrue(deps.contains("com.example.Result"), "Factory should depend on Result via return type");
    }

    @Test
    void testResolveBodyObjectCreationDependency() {
        CodeClass engine = new CodeClass("Engine", "com.example.Engine", "com.example");
        engine.setAstBody("package com.example;\npublic class Engine {}");

        CodeClass car = new CodeClass("Car", "com.example.Car", "com.example");
        car.setAstBody("""
            package com.example;
            import com.example.Engine;
            public class Car {
                public void start() {
                    Engine e = new Engine();
                }
            }
            """);

        when(classRepo.findAll()).thenReturn(List.of(engine, car));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(car);

        assertTrue(deps.contains("com.example.Engine"), "Car should depend on Engine via object creation");
    }

    @Test
    void testResolveAnnotationDependency() {
        CodeClass ann = new CodeClass("RestController", "org.springframework.web.bind.annotation.RestController", "org.springframework.web.bind.annotation");
        ann.setAstBody("package org.springframework.web.bind.annotation;\npublic @interface RestController {}");

        CodeClass ctrl = new CodeClass("MyController", "com.example.MyController", "com.example");
        ctrl.setAstBody("""
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            public class MyController {}
            """);

        when(classRepo.findAll()).thenReturn(List.of(ann, ctrl));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(ctrl);

        assertTrue(deps.contains("org.springframework.web.bind.annotation.RestController"), "Should resolve annotation dependency");
    }

    @Test
    void testTransitiveDependencyClosure() {
        CodeClass classC = new CodeClass("C", "com.example.C", "com.example");
        classC.setAstBody("package com.example;\npublic class C {}");

        CodeClass classB = new CodeClass("B", "com.example.B", "com.example");
        classB.setAstBody("""
            package com.example;
            import com.example.C;
            public class B {
                private C c;
            }
            """);

        CodeClass classA = new CodeClass("A", "com.example.A", "com.example");
        classA.setAstBody("""
            package com.example;
            import com.example.B;
            public class A {
                private B b;
            }
            """);

        when(classRepo.findAll()).thenReturn(List.of(classA, classB, classC));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Map<String, Set<CodeClass>> allDeps = resolver.resolveAllDependencies();

        Set<CodeClass> aDeps = allDeps.get("com.example.A");
        assertNotNull(aDeps, "A should have dependencies");
        assertTrue(aDeps.stream().anyMatch(d -> d.getQualifiedName().equals("com.example.B")),
                "A should directly depend on B");
        assertTrue(aDeps.stream().anyMatch(d -> d.getQualifiedName().equals("com.example.C")),
                "A should transitively depend on C");
    }

    @Test
    void testSelfReferenceExcluded() {
        CodeClass self = new CodeClass("Self", "com.example.Self", "com.example");
        self.setAstBody("""
            package com.example;
            public class Self {
                public Self getInstance() { return this; }
            }
            """);

        when(classRepo.findAll()).thenReturn(List.of(self));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(self);

        assertFalse(deps.contains("com.example.Self"), "Self-reference should be excluded");
    }

    @Test
    void testGenericTypeDependency() {
        CodeClass entity = new CodeClass("Entity", "com.example.Entity", "com.example");
        entity.setAstBody("package com.example;\npublic class Entity {}");

        CodeClass repo = new CodeClass("Repo", "com.example.Repo", "com.example");
        repo.setAstBody("""
            package com.example;
            import java.util.List;
            import com.example.Entity;
            public class Repo {
                private List<Entity> items;
            }
            """);

        when(classRepo.findAll()).thenReturn(List.of(entity, repo));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(repo);

        assertTrue(deps.contains("com.example.Entity"), "Repo should depend on Entity via generic type argument");
    }

    @Test
    void testNoDependenciesForEmptyClass() {
        CodeClass empty = new CodeClass("Empty", "com.example.Empty", "com.example");
        empty.setAstBody("""
            package com.example;
            public class Empty {}
            """);

        when(classRepo.findAll()).thenReturn(List.of(empty));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(empty);

        assertTrue(deps.isEmpty(), "Empty class without deps should have empty dependency set");
    }

    @Test
    void testMultipleDependenciesResolved() {
        CodeClass dep1 = new CodeClass("Dep1", "com.example.Dep1", "com.example");
        dep1.setAstBody("package com.example;\npublic class Dep1 {}");
        CodeClass dep2 = new CodeClass("Dep2", "com.example.Dep2", "com.example");
        dep2.setAstBody("package com.example;\npublic class Dep2 {}");
        CodeClass dep3 = new CodeClass("Dep3", "com.example.Dep3", "com.example");
        dep3.setAstBody("package com.example;\npublic class Dep3 {}");

        CodeClass main = new CodeClass("Main", "com.example.Main", "com.example");
        main.setAstBody("""
            package com.example;
            import com.example.Dep1;
            import com.example.Dep2;
            import com.example.Dep3;
            public class Main extends Dep1 implements Dep2 {
                private Dep3 field;
            }
            """);

        when(classRepo.findAll()).thenReturn(List.of(dep1, dep2, dep3, main));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Map<String, Set<CodeClass>> allDeps = resolver.resolveAllDependencies();

        Set<CodeClass> mainDeps = allDeps.get("com.example.Main");
        assertNotNull(mainDeps);
        assertTrue(mainDeps.size() >= 3, "Main should have 3+ dependencies");
    }

    @Test
    void testDependencyCountsInAllClasses() {
        CodeClass a = new CodeClass("A", "com.example.A", "com.example");
        a.setAstBody("package com.example;\npublic class A {}");
        CodeClass b = new CodeClass("B", "com.example.B", "com.example");
        b.setAstBody("""
            package com.example;
            import com.example.A;
            public class B { private A a; }
            """);

        when(classRepo.findAll()).thenReturn(List.of(a, b));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);

        Map<String, Set<CodeClass>> allDeps = resolver.resolveAllDependencies();

        assertEquals(2, allDeps.size(), "Should have entries for all 2 classes");
    }

    @Test
    void testResolveWithEmptyAstBody() {
        CodeClass empty = new CodeClass("Empty", "com.example.Empty", "com.example");
        empty.setAstBody("");

        when(classRepo.findAll()).thenReturn(List.of(empty));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(empty);

        assertTrue(deps.isEmpty(), "Empty astBody should produce no dependencies");
    }

    @Test
    void testResolveWithNullAstBody() {
        CodeClass nullBody = new CodeClass("NullBody", "com.example.NullBody", "com.example");
        nullBody.setAstBody(null);

        when(classRepo.findAll()).thenReturn(List.of(nullBody));

        AstDependencyResolver resolver = new AstDependencyResolver(classRepo, methodRepo, fieldRepo);
        Set<String> deps = resolver.resolveDependencies(nullBody);

        assertTrue(deps.isEmpty(), "Null astBody should produce no dependencies");
    }
}
