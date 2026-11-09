package finelock.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * FineLock: automatically refactoring coarse-grained {@code synchronized} locks into
 * fine-grained {@code ReentrantReadWriteLock} locks (Zhang, Shao, Zhai, Ma; ISSTA 2020).
 *
 * <pre>{@code
 * RefactoringResult r = FineLock.refactor(List.of(Path.of("src/main/java")), List.of(), p -> true, new FineLockOptions());
 * r.changedFiles().forEach((path, text) -> Files.writeString(path, text));
 * }</pre>
 */
public final class FineLock {

    private FineLock() {}

    /**
     * Analyses all sources under {@code sourceRoots} and computes the refactoring.
     *
     * @param sourceRoots directories holding the project's .java files (package roots)
     * @param classpath   jars used to resolve library types; JDK classes are always available
     * @param scope       which files to refactor; a lock group that has any section in scope is
     *                    refactored completely, which can touch files outside the scope
     */
    public static RefactoringResult refactor(List<Path> sourceRoots, List<Path> classpath, Predicate<Path> scope,
                                             FineLockOptions options) throws IOException {
        long t0 = System.currentTimeMillis();
        ProjectIndex index = new ProjectIndex(sourceRoots, classpath);
        SideEffectAnalysis effects = new SideEffectAnalysis(index, new LibraryModel(options.extraReadOnlyMethods));
        effects.computeFixpoint();

        LockLocator locator = new LockLocator(index);
        locator.run();
        LocksetAnalysis lockset = new LocksetAnalysis(index);
        lockset.run(locator.sections, locator.exclusions);

        Transformer transformer = new Transformer(index, effects, options);
        List<CriticalSection> reported = new ArrayList<>();
        for (CriticalSection s : locator.sections) {
            if (s.group == null && scope.test(s.file.path())) {
                s.note = "not refactored: " + s.unsupportedReason;
                reported.add(s);
            }
        }
        for (LockGroup g : lockset.groups.values()) {
            if (g.sections.stream().noneMatch(s -> scope.test(s.file.path()))) continue;
            for (CriticalSection s : g.sections) {
                if (s.mergedInto == null) transformer.infer(s);
                reported.add(s);
            }
            if (g.excluded()) {
                for (CriticalSection s : g.sections) {
                    s.note = "not refactored: " + String.join("; ", g.excludedBecause);
                }
            } else {
                transformer.transform(g);
            }
        }
        reported.sort(Comparator.comparing((CriticalSection s) -> s.file.path().toString()).thenComparingInt(CriticalSection::line));

        Map<Path, String> changed = new LinkedHashMap<>();
        Map<Path, String> originals = new LinkedHashMap<>();
        for (SourceFile f : index.files) {
            if (!f.hasEdits()) continue;
            String nt = f.newText();
            if (!nt.equals(f.text)) {
                changed.put(f.path(), nt);
                originals.put(f.path(), f.text);
            }
        }
        List<String> warnings = new ArrayList<>(index.warnings);
        warnings.addAll(locator.warnings);
        return new RefactoringResult(reported.stream().map(SectionReport::of).toList(), changed, originals, warnings,
                System.currentTimeMillis() - t0);
    }

    public static RefactoringResult refactor(List<Path> sourceRoots, FineLockOptions options) throws IOException {
        return refactor(sourceRoots, List.of(), p -> true, options);
    }
}
