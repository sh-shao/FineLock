package finelock.cli;

import finelock.core.FineLock;
import finelock.core.FineLockOptions;
import finelock.core.RefactoringResult;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Command line front end: {@code finelock [options] <source-root>...}. */
public final class Main {

    private static final String USAGE = """
            Usage: finelock [options] <source-root>...

            Refactors synchronized methods/blocks into ReentrantReadWriteLock read, write,
            splitting and downgrading locks (FineLock, ISSTA 2020).
            By default nothing is written: the report and (with --diff) the diff are printed.

            Options:
              --apply              write the refactored files in place
              --out <dir>          write the refactored files under <dir> instead (paths relative to the source root)
              --diff               print a unified diff of all changes
              --report <file>      write the Markdown report to <file>
              --cp <path>          classpath (jars, separated by '%s') used to resolve library types
              --only <glob>        only refactor files matching the glob, e.g. '**/cache/*.java'
              --no-downgrade       do not infer downgrading locks
              --no-split           do not infer splitting locks
              --fair               create fair locks
              --read-only <T#m>    treat library method T#m (or T#*) as read-only; repeatable
              -h, --help           show this help
            """.formatted(File.pathSeparator);

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        FineLockOptions opts = new FineLockOptions();
        List<Path> roots = new ArrayList<>();
        List<Path> cp = new ArrayList<>();
        boolean apply = false, diff = false;
        Path outDir = null, report = null;
        List<PathMatcher> only = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h", "--help" -> {
                        out.print(USAGE);
                        return 0;
                    }
                    case "--apply" -> apply = true;
                    case "--diff" -> diff = true;
                    case "--out" -> outDir = Path.of(args[++i]);
                    case "--report" -> report = Path.of(args[++i]);
                    case "--cp" -> {
                        for (String p : args[++i].split(File.pathSeparator)) if (!p.isBlank()) cp.add(Path.of(p));
                    }
                    case "--only" -> only.add(FileSystems.getDefault().getPathMatcher("glob:" + args[++i]));
                    case "--no-downgrade" -> opts.enableDowngrade = false;
                    case "--no-split" -> opts.enableSplitting = false;
                    case "--fair" -> opts.fairLocks = true;
                    case "--read-only" -> opts.extraReadOnlyMethods.add(args[++i]);
                    default -> {
                        if (a.startsWith("-")) throw new IllegalArgumentException("unknown option " + a);
                        roots.add(Path.of(a));
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            err.println("missing value for " + args[args.length - 1]);
            return 2;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.print(USAGE);
            return 2;
        }
        if (roots.isEmpty()) {
            err.print(USAGE);
            return 2;
        }
        for (Path r : roots) {
            if (!Files.isDirectory(r)) {
                err.println("not a directory: " + r);
                return 2;
            }
        }
        if (apply && outDir != null) {
            err.println("use either --apply or --out");
            return 2;
        }
        Predicate<Path> scope = only.isEmpty() ? p -> true : p -> only.stream().anyMatch(m -> m.matches(p));

        try {
            RefactoringResult r = FineLock.refactor(roots, cp, scope, opts);
            if (diff) {
                for (Map.Entry<Path, String> e : r.changedFiles().entrySet()) {
                    out.print(UnifiedDiff.diff(display(e.getKey()), r.originalFiles().get(e.getKey()), e.getValue()));
                }
            }
            String md = r.toMarkdown();
            if (report != null) {
                Files.writeString(report, md);
            }
            out.println(summaryLine(r));
            if (report == null && !diff) out.println(md);
            if (apply) {
                for (Map.Entry<Path, String> e : r.changedFiles().entrySet()) Files.writeString(e.getKey(), e.getValue());
                out.println("wrote " + r.changedFiles().size() + " file(s)");
            } else if (outDir != null) {
                for (Map.Entry<Path, String> e : r.changedFiles().entrySet()) {
                    Path target = outDir.resolve(relativize(roots, e.getKey()));
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, e.getValue());
                }
                out.println("wrote " + r.changedFiles().size() + " file(s) to " + outDir);
            } else if (!r.changedFiles().isEmpty()) {
                out.println("dry run: use --apply to write the changes");
            }
            return 0;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static String summaryLine(RefactoringResult r) {
        RefactoringResult.Summary s = r.summary();
        return String.format("FineLock: %d synchronized lock(s): %d downgrading, %d splitting, %d read, %d write, "
                        + "%d merged, %d unchanged (%d ms)", s.synchronizedLocks(), s.downgrading(), s.splitting(),
                s.read(), s.write(), s.merged(), s.notRefactored(), r.millis());
    }

    private static Path relativize(List<Path> roots, Path file) {
        for (Path r : roots) {
            Path abs = r.toAbsolutePath().normalize();
            if (file.startsWith(abs)) return abs.relativize(file);
        }
        return file.getFileName();
    }

    private static String display(Path p) {
        Path cwd = Path.of("").toAbsolutePath();
        return p.startsWith(cwd) ? cwd.relativize(p).toString() : p.toString();
    }
}
