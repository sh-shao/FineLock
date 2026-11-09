package finelock.core;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One parsed source file plus the text edits FineLock wants to make to it.
 *
 * <p>Edits are applied to the original text, so code FineLock does not touch keeps its exact
 * formatting and comments.
 */
public final class SourceFile {

    /** Replace {@code [start, end)} of the original text. */
    public record Edit(int start, int end, String text) {
        boolean contains(Edit o) {
            if (this == o) return false;
            if (o.start == o.end) return start < o.start && o.start < end; // insertions on the boundary stay
            return start <= o.start && o.end <= end;
        }
    }

    final Path path;
    final String text;
    final CompilationUnit cu;
    final String lineSep;
    final String indentUnit;
    private final int[] lineStarts;
    private final List<Edit> edits = new ArrayList<>();

    SourceFile(Path path, String text, CompilationUnit cu) {
        this.path = path;
        this.text = text;
        this.cu = cu;
        this.lineSep = text.contains("\r\n") ? "\r\n" : "\n";
        this.indentUnit = detectIndent(text);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                starts.add(i + 1);
            } else if (ch == '\n') {
                starts.add(i + 1);
            }
        }
        lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    public Path path() {
        return path;
    }

    public String originalText() {
        return text;
    }

    int offset(Position p) {
        return lineStarts[p.line - 1] + p.column - 1;
    }

    /** Start offset (inclusive) of a node. */
    int start(Node n) {
        return offset(n.getRange().orElseThrow().begin);
    }

    /** End offset (exclusive) of a node. */
    int end(Node n) {
        return offset(n.getRange().orElseThrow().end) + 1;
    }

    int lineOf(int offset) {
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** Leading whitespace of the line that contains {@code offset}. */
    String indentAt(int offset) {
        int ls = lineStarts[lineOf(offset)];
        int i = ls;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return text.substring(ls, i);
    }

    /** Leading whitespace of the line on which the first non-blank char at or after {@code from} sits. */
    String indentOfFirstCode(int from, int to) {
        int i = from;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        return indentAt(Math.min(i, Math.max(from, to - 1)));
    }

    /** Records an edit. Edits already recorded inside its range are dropped: the new one replaces them. */
    void addEdit(Edit e) {
        edits.removeIf(e::contains);
        edits.add(e);
    }

    boolean hasEdits() {
        return !edits.isEmpty();
    }

    /**
     * The text of {@code [start, end)} with the recorded edits inside that range applied, plus
     * {@code extra} edits. Recorded edits that fall inside an extra edit are skipped: the extra
     * edit is expected to have been built from {@link #text} already.
     */
    String text(int start, int end, List<Edit> extra) {
        List<Edit> all = new ArrayList<>(extra);
        for (Edit e : edits) {
            if (e.start >= start && e.end <= end && extra.stream().noneMatch(x -> x.contains(e))) {
                all.add(e);
            }
        }
        all.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        StringBuilder sb = new StringBuilder();
        int pos = start;
        for (Edit e : all) {
            if (e.start < pos) continue; // overlapping edits cannot happen; be defensive
            sb.append(text, pos, e.start).append(e.text);
            pos = e.end;
        }
        sb.append(text, pos, end);
        return sb.toString();
    }

    String text(int start, int end) {
        return text(start, end, List.of());
    }

    /** The whole file with all edits applied. */
    public String newText() {
        return text(0, text.length());
    }

    /**
     * Re-indents a piece of code: the first line and every line that starts with {@code from}
     * get {@code to} in place of it. Leading and trailing blank lines are removed. Lines are
     * joined with the file's own line separator.
     */
    String reindent(String code, String from, String to) {
        String s = code.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = s.split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean first = true;
        for (String line : lines) {
            if (first) {
                if (line.isBlank()) continue;
                out.add(to + line.stripLeading());
                first = false;
            } else if (line.isBlank()) {
                out.add("");
            } else if (line.startsWith(from)) {
                out.add(to + line.substring(from.length()));
            } else {
                out.add(line);
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        if (!out.isEmpty()) {
            int last = out.size() - 1;
            out.set(last, out.get(last).stripTrailing());
        }
        return String.join(lineSep, out);
    }

    private static String detectIndent(String text) {
        int min = Integer.MAX_VALUE;
        for (String line : text.split("\r?\n|\r")) {
            if (line.isBlank()) continue;
            if (line.startsWith("\t")) return "\t";
            int n = 0;
            while (n < line.length() && line.charAt(n) == ' ') n++;
            if (n > 0 && n < line.length() && line.charAt(n) != '*') {
                min = Math.min(min, n);
            }
        }
        return min == Integer.MAX_VALUE || min > 8 ? "    " : " ".repeat(min);
    }
}
