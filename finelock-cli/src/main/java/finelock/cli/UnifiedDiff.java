package finelock.cli;

import java.util.ArrayList;
import java.util.List;

/** Minimal unified diff (LCS on the lines between the common prefix and suffix). */
final class UnifiedDiff {

    private static final int CONTEXT = 3;

    private UnifiedDiff() {}

    static String diff(String name, String a, String b) {
        String[] x = a.split("\r?\n", -1), y = b.split("\r?\n", -1);
        int pre = 0;
        while (pre < x.length && pre < y.length && x[pre].equals(y[pre])) pre++;
        int suf = 0;
        while (suf < x.length - pre && suf < y.length - pre && x[x.length - 1 - suf].equals(y[y.length - 1 - suf])) suf++;
        int n = x.length - pre - suf, m = y.length - pre - suf;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = x[pre + i].equals(y[pre + j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        // edit script over the whole file: ' ', '-', '+'
        List<char[]> ops = new ArrayList<>(); // {op} with line index into x or y stored separately
        List<String> lines = new ArrayList<>();
        for (int k = 0; k < pre; k++) { ops.add(new char[]{' '}); lines.add(x[k]); }
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && x[pre + i].equals(y[pre + j])) { ops.add(new char[]{' '}); lines.add(x[pre + i]); i++; j++; }
            else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) { ops.add(new char[]{'+'}); lines.add(y[pre + j]); j++; }
            else { ops.add(new char[]{'-'}); lines.add(x[pre + i]); i++; }
        }
        for (int k = x.length - suf; k < x.length; k++) { ops.add(new char[]{' '}); lines.add(x[k]); }

        StringBuilder sb = new StringBuilder();
        String label = name.startsWith("/") ? name.substring(1) : name;
        sb.append("--- a/").append(label).append('\n').append("+++ b/").append(label).append('\n');
        int total = ops.size();
        int k = 0;
        while (k < total) {
            if (ops.get(k)[0] == ' ') { k++; continue; }
            int start = Math.max(0, k - CONTEXT);
            int end = k;
            // extend the hunk while changes are within 2*CONTEXT lines of each other
            int lastChange = k;
            while (end < total) {
                if (ops.get(end)[0] != ' ') lastChange = end;
                else if (end - lastChange > 2 * CONTEXT) break;
                end++;
            }
            end = Math.min(total, lastChange + CONTEXT + 1);
            int aStart = 1, bStart = 1;
            for (int q = 0; q < start; q++) {
                if (ops.get(q)[0] != '+') aStart++;
                if (ops.get(q)[0] != '-') bStart++;
            }
            int aLen = 0, bLen = 0;
            for (int q = start; q < end; q++) {
                if (ops.get(q)[0] != '+') aLen++;
                if (ops.get(q)[0] != '-') bLen++;
            }
            sb.append("@@ -").append(aStart).append(',').append(aLen).append(" +").append(bStart).append(',').append(bLen).append(" @@\n");
            for (int q = start; q < end; q++) sb.append(ops.get(q)[0]).append(lines.get(q)).append('\n');
            k = end;
        }
        return sb.toString();
    }
}
