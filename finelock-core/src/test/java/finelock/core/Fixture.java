package finelock.core;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Writes test sources to a temp dir, runs FineLock, and compiles the result. */
final class Fixture {

    final Path root;
    RefactoringResult result;

    Fixture(Path tmp) {
        this.root = tmp.resolve("src");
    }

    Fixture file(String path, String code) throws IOException {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, code);
        return this;
    }

    Fixture run() throws IOException {
        return run(new FineLockOptions());
    }

    Fixture run(FineLockOptions o) throws IOException {
        result = FineLock.refactor(List.of(root), o);
        return this;
    }

    /** Writes the refactored files back and compiles everything; fails with the diagnostics and the code. */
    Path applyAndCompile() throws IOException {
        for (Map.Entry<Path, String> e : result.changedFiles().entrySet()) Files.writeString(e.getKey(), e.getValue());
        Path out = root.getParent().resolve("classes");
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, null, null)) {
            boolean ok = javac.getTask(null, fm, diags, List.of("-d", out.toString(), "-proc:none"), null,
                    fm.getJavaFileObjectsFromPaths(files)).call();
            if (!ok) {
                StringBuilder sb = new StringBuilder("refactored code does not compile:\n");
                for (Diagnostic<?> d : diags.getDiagnostics()) sb.append(d).append('\n');
                for (Path p : files) sb.append("---- ").append(p.getFileName()).append('\n').append(Files.readString(p));
                fail(sb.toString());
            }
        }
        return out;
    }

    ClassLoader load() throws IOException {
        Path out = applyAndCompile();
        return new URLClassLoader(new URL[]{out.toUri().toURL()}, getClass().getClassLoader());
    }

    String text(String path) {
        Path p = root.resolve(path).toAbsolutePath().normalize();
        String t = result.changedFiles().get(p);
        if (t != null) return t;
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    SectionReport section(String memberContains) {
        List<SectionReport> found = new ArrayList<>();
        for (SectionReport s : result.sections()) if (s.member().contains(memberContains)) found.add(s);
        assertEquals(1, found.size(), () -> "sections matching '" + memberContains + "': " + result.sections());
        return found.get(0);
    }

    Fixture expect(String member, LockMode mode) {
        SectionReport s = section(member);
        assertTrue(s.refactored(), () -> member + " not refactored: " + s.note());
        assertEquals(mode, s.mode(), () -> member + " pattern=" + s.pattern() + " note=" + s.note());
        return this;
    }
}
