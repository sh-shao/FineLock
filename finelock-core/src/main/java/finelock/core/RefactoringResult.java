package finelock.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Outcome of a FineLock run. Nothing is written to disk; callers apply {@link #changedFiles()}. */
public record RefactoringResult(List<SectionReport> sections, Map<Path, String> changedFiles,
                                Map<Path, String> originalFiles, List<String> warnings, long millis) {

    /** Counts in the shape of Table 1 of the paper. */
    public record Summary(int synchronizedLocks, int downgrading, int splitting, int read, int write,
                          int merged, int notRefactored) {
        public int fineGrained() {
            return downgrading + splitting;
        }

        public int readWrite() {
            return read + write;
        }
    }

    public Summary summary() {
        int d = 0, sp = 0, r = 0, w = 0, m = 0, n = 0;
        for (SectionReport s : sections) {
            if (!s.refactored()) n++;
            else if (s.mode() == null) m++;
            else if (s.mode() == LockMode.DOWNGRADE) d++;
            else if (s.mode().isSplitting()) sp++;
            else if (s.mode() == LockMode.READ) r++;
            else w++;
        }
        return new Summary(sections.size(), d, sp, r, w, m, n);
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        Summary s = summary();
        sb.append("# FineLock report\n\n");
        sb.append("| synchronized | downgrading | splitting | read | write | merged | not refactored | fine-grained | read/write |\n");
        sb.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        sb.append(String.format("| %d | %d | %d | %d | %d | %d | %d | %s | %s |%n%n", s.synchronizedLocks(), s.downgrading(),
                s.splitting(), s.read(), s.write(), s.merged(), s.notRefactored(),
                pct(s.fineGrained(), s.synchronizedLocks()), pct(s.readWrite(), s.synchronizedLocks())));
        sb.append(String.format("%d file(s) changed in %d ms.%n%n", changedFiles.size(), millis));
        sb.append("| file:line | member | monitor | pattern | lock | note |\n|---|---|---|---|---|---|\n");
        for (SectionReport r : sections) {
            sb.append("| ").append(r.file().getFileName()).append(':').append(r.line())
                    .append(" | `").append(r.member().replace("|", "\\|")).append('`')
                    .append(" | ").append(r.monitor())
                    .append(" | ").append(r.pattern() == null ? "" : "`" + r.pattern() + "`")
                    .append(" | ").append(r.refactored() ? (r.mode() == null ? "merged" : r.mode().description()) : "unchanged")
                    .append(" | ").append(r.note() == null ? "" : r.note().replace("|", "\\|"))
                    .append(" |\n");
        }
        if (!warnings.isEmpty()) {
            sb.append("\n## Warnings\n\n");
            for (String w : warnings) sb.append("- ").append(w).append('\n');
        }
        return sb.toString();
    }

    private static String pct(int a, int b) {
        return b == 0 ? "-" : String.format("%.1f%%", 100.0 * a / b);
    }
}
