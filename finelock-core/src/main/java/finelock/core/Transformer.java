package finelock.core;

import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.PatternExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lock inference and Transformation (Sections 3.2 and 3.3).
 *
 * <p>For each section the pattern is run through the {@link PushdownAutomaton}; the inferred lock is
 * then checked against the code (the automaton only sees r/w/c/e) and the section is rewritten.
 * All generated code puts unlocks in {@code finally} blocks. Rewriting is done with text templates
 * so the user's own code keeps its formatting and comments.
 */
final class Transformer {

    static final String LOCK_CLASS = "java.util.concurrent.locks.ReentrantReadWriteLock";
    private static final String LOCK_SIMPLE = "ReentrantReadWriteLock";

    private final ProjectIndex index;
    private final SideEffectAnalysis effects;
    private final PatternGenerator patterns;
    private final FineLockOptions opts;
    private final Map<SourceFile, String> lockTypeName = new HashMap<>();
    private final Map<SourceFile, Set<String>> reservedNames = new HashMap<>();

    Transformer(ProjectIndex index, SideEffectAnalysis effects, FineLockOptions opts) {
        this.index = index;
        this.effects = effects;
        this.patterns = new PatternGenerator(effects);
        this.opts = opts;
    }

    // ------------------------------------------------------------------ inference

    /** Runs the pattern generator and the automaton, then checks that the inferred lock is safe here. */
    void infer(CriticalSection s) {
        List<Statement> st = s.body.getStatements();
        List<String> parts = new ArrayList<>();
        for (Statement x : st) parts.add(patterns.of(x));
        s.pattern = String.join("", parts);
        s.automaton = PushdownAutomaton.run(s.pattern);
        LockMode m = s.automaton.mode();
        String note = null;
        if (s.automaton.halted()) note = "pattern halts the automaton (V): write lock for safety";
        else if (s.automaton.rejected()) note = "pattern not accepted by the automaton: write lock for safety";

        if (m.isSplitting() && !opts.enableSplitting) {
            m = LockMode.WRITE;
            note = "splitting locks are disabled";
        } else if (m == LockMode.DOWNGRADE && !opts.enableDowngrade) {
            m = LockMode.WRITE;
            note = "downgrading locks are disabled";
        }
        String why = switch (m) {
            case SPLIT_READ_WRITE -> {
                int b = firstWith(parts);
                yield independence(st.subList(0, b), st.subList(b, st.size()));
            }
            case SPLIT_WRITE_READ -> lastWith(parts) >= st.size() - 1 ? "no reads after the writes" : null;
            case SPLIT_CONDITIONAL_WRITE, DOWNGRADE -> checkConditional(st, parts);
            default -> null;
        };
        if (why != null) {
            note = m.description() + " rejected: " + why + "; write lock instead";
            m = LockMode.WRITE;
        }
        s.mode = m;
        s.note = note;
    }

    private String checkConditional(List<Statement> st, List<String> parts) {
        int k = -1;
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).indexOf('w') >= 0) {
                if (k >= 0) return "writes are spread over several statements";
                k = i;
            }
        }
        if (k < 0 || !(st.get(k) instanceof IfStmt top)) return "the writes are not inside one if statement";
        for (Expression cond : chain(top).conditions) {
            if (!effects.isPure(cond)) return "the condition '" + cond + "' cannot be re-evaluated without side effects";
            if (cond.findFirst(PatternExpr.class).isPresent()) return "the condition declares a pattern variable";
        }
        return independence(st.subList(0, k), st.subList(k, st.size()));
    }

    /**
     * The statements run under the read lock before the lock is released must not influence what
     * runs after it: no early exits and no locals computed there that are used later. Otherwise the
     * later code would act on a stale view of the shared state.
     */
    private String independence(List<Statement> before, List<Statement> after) {
        for (Statement s : before) {
            String exit = abruptExit(s);
            if (exit != null) return "a statement before the lock change may " + exit;
        }
        Set<String> written = new HashSet<>();
        for (Statement s : before) written.addAll(SideEffectAnalysis.namesWritten(s));
        Set<String> read = new HashSet<>();
        for (Statement s : after) read.addAll(SideEffectAnalysis.namesRead(s));
        written.retainAll(read);
        if (!written.isEmpty()) {
            return "'" + String.join("', '", written.stream().sorted().toList())
                    + "' is computed under the read lock and used after the lock changes";
        }
        return null;
    }

    /** Returns what kind of exit leaves {@code s} early (return, throw, break/continue to outside), or null. */
    private static String abruptExit(Statement s) {
        for (Node n : s.findAll(Node.class, x -> x instanceof ReturnStmt || x instanceof ThrowStmt
                || x instanceof BreakStmt || x instanceof ContinueStmt)) {
            if (insideNestedCode(n, s)) continue;
            if (n instanceof ReturnStmt) return "return";
            if (n instanceof ThrowStmt) return "throw";
            if (n instanceof BreakStmt b) {
                if (targetInside(n, b.getLabel().map(SimpleName::getIdentifier).orElse(null), s, true)) continue;
                return "break out of the section";
            }
            ContinueStmt c = (ContinueStmt) n;
            if (targetInside(n, c.getLabel().map(SimpleName::getIdentifier).orElse(null), s, false)) continue;
            return "continue outside the section";
        }
        return null;
    }

    private static boolean insideNestedCode(Node n, Node stop) {
        for (Node cur = n.getParentNode().orElse(null); cur != null && cur != stop; cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof LambdaExpr || cur instanceof TypeDeclaration<?>
                    || (cur instanceof ObjectCreationExpr oc && oc.getAnonymousClassBody().isPresent())) {
                return true;
            }
        }
        return false;
    }

    private static boolean targetInside(Node jump, String label, Node stop, boolean isBreak) {
        for (Node cur = jump.getParentNode().orElse(null); cur != null; cur = cur.getParentNode().orElse(null)) {
            boolean match = label != null
                    ? cur instanceof LabeledStmt ls && ls.getLabel().getIdentifier().equals(label)
                    : cur instanceof ForStmt || cur instanceof ForEachStmt || cur instanceof WhileStmt
                    || cur instanceof DoStmt || (isBreak && cur instanceof SwitchStmt);
            if (match) return true;
            if (cur == stop) return false;
        }
        return false;
    }

    private record Chain(List<Expression> conditions, List<Statement> branches) {}

    /** Flattens {@code if (a) X else if (b) Y else Z} into conditions [a, b] and branches [X, Y, Z]. */
    private Chain chain(IfStmt top) {
        List<Expression> conds = new ArrayList<>();
        List<Statement> branches = new ArrayList<>();
        IfStmt cur = top;
        while (true) {
            conds.add(cur.getCondition());
            branches.add(cur.getThenStmt());
            if (cur.getElseStmt().isEmpty()) break;
            Statement e = cur.getElseStmt().get();
            if (e instanceof IfStmt next && !effects.writes(next.getCondition())) {
                cur = next;
            } else {
                branches.add(e);
                break;
            }
        }
        return new Chain(conds, branches);
    }

    private static int firstWith(List<String> parts) {
        for (int i = 0; i < parts.size(); i++) if (parts.get(i).indexOf('w') >= 0) return i;
        return -1;
    }

    private static int lastWith(List<String> parts) {
        for (int i = parts.size() - 1; i >= 0; i--) if (parts.get(i).indexOf('w') >= 0) return i;
        return -1;
    }

    // ------------------------------------------------------------------ transformation

    void transform(LockGroup g) {
        List<CriticalSection> sorted = new ArrayList<>(g.sections);
        sorted.sort(Comparator.comparingInt(CriticalSection::length));
        g.lockField = chooseFieldName(g);
        for (CriticalSection s : sorted) {
            if (s.mergedInto != null) {
                SourceFile f = s.file;
                f.addEdit(new SourceFile.Edit(f.start(s.node), f.start(s.body), ""));
                s.mode = null;
                s.note = "nested in the section at line " + s.mergedInto.line() + " on the same lock; merged into it";
                s.refactored = true;
                continue;
            }
            emit(g, s);
            s.refactored = true;
        }
        insertField(g);
    }

    private void emit(LockGroup g, CriticalSection s) {
        SourceFile f = s.file;
        String nl = f.lineSep;
        String u = f.indentUnit;
        String lock = lockAccess(g, s);
        BlockStmt body = s.body;
        int bodyStart = f.start(body), bodyEnd = f.end(body);
        if (s.isMethod()) {
            String close = f.indentAt(bodyEnd - 1);
            List<String> lines = generate(s, lock, close + u, null);
            f.addEdit(new SourceFile.Edit(bodyStart, bodyEnd, "{" + nl + String.join(nl, lines) + nl + close + "}"));
            MethodDeclaration md = (MethodDeclaration) s.node;
            for (Modifier m : md.getModifiers()) {
                if (m.getKeyword() == Modifier.Keyword.SYNCHRONIZED) {
                    int a = f.start(m), b = f.end(m);
                    while (b < f.text.length() && (f.text.charAt(b) == ' ' || f.text.charAt(b) == '\t')) b++;
                    f.addEdit(new SourceFile.Edit(a, b, ""));
                }
            }
        } else {
            int stmtStart = f.start(s.node), stmtEnd = f.end(s.node);
            String base = f.indentAt(stmtStart);
            Node parent = s.node.getParentNode().orElse(null);
            boolean wrap = s.mode == LockMode.SPLIT_WRITE_READ
                    || !(parent instanceof BlockStmt || parent instanceof SwitchEntry);
            String flag = s.mode == LockMode.SPLIT_WRITE_READ ? uniqueLocal(f, "downgraded") : null;
            List<String> lines = generate(s, lock, wrap ? base + u : base, flag);
            String text;
            if (wrap) {
                text = "{" + nl + String.join(nl, lines) + nl + base + "}";
            } else {
                text = String.join(nl, lines).substring(base.length());
            }
            f.addEdit(new SourceFile.Edit(stmtStart, stmtEnd, text));
        }
    }

    /** The statements that replace the section body, each line already indented with {@code sp}. */
    private List<String> generate(CriticalSection s, String lock, String sp, String flag) {
        SourceFile f = s.file;
        String nl = f.lineSep;
        String u = f.indentUnit;
        String ip = sp + u;
        int innerStart = f.start(s.body) + 1, innerEnd = f.end(s.body) - 1;
        List<Statement> st = s.body.getStatements();
        List<String> out = new ArrayList<>();
        switch (s.mode) {
            case READ -> locked(out, sp, ip, lock, "read", content(f, innerStart, innerEnd, List.of(), ip));
            case WRITE -> locked(out, sp, ip, lock, "write", content(f, innerStart, innerEnd, List.of(), ip));
            case SPLIT_READ_WRITE -> {
                List<String> parts = st.stream().map(patterns::of).toList();
                int split = f.end(st.get(firstWith(parts) - 1));
                locked(out, sp, ip, lock, "read", content(f, innerStart, split, List.of(), ip));
                locked(out, sp, ip, lock, "write", content(f, split, innerEnd, List.of(), ip));
            }
            case SPLIT_WRITE_READ -> {
                if (flag == null) flag = uniqueLocal(f, "downgraded");
                List<String> parts = st.stream().map(patterns::of).toList();
                Statement last = st.get(lastWith(parts));
                String lw = f.indentAt(f.start(last));
                String downgrade = nl + lw + lock + ".readLock().lock();"
                        + nl + lw + lock + ".writeLock().unlock();"
                        + nl + lw + flag + " = true;";
                int at = f.end(last);
                out.add(sp + lock + ".writeLock().lock();");
                out.add(sp + "boolean " + flag + " = false;");
                out.add(sp + "try {");
                addIfPresent(out, content(f, innerStart, innerEnd, List.of(new SourceFile.Edit(at, at, downgrade)), ip));
                out.add(sp + "} finally {");
                out.add(ip + "if (" + flag + ") {");
                out.add(ip + u + lock + ".readLock().unlock();");
                out.add(ip + "} else {");
                out.add(ip + u + lock + ".writeLock().unlock();");
                out.add(ip + "}");
                out.add(sp + "}");
            }
            case SPLIT_CONDITIONAL_WRITE, DOWNGRADE -> {
                List<String> parts = st.stream().map(patterns::of).toList();
                IfStmt top = (IfStmt) st.get(firstWith(parts));
                List<SourceFile.Edit> upgrades = new ArrayList<>();
                for (Statement branch : chain(top).branches) {
                    if (effects.writes(branch)) {
                        upgrades.add(new SourceFile.Edit(f.start(branch), f.end(branch), upgradeBlock(f, top, branch, lock)));
                    }
                }
                locked(out, sp, ip, lock, "read", content(f, innerStart, innerEnd, upgrades, ip));
            }
        }
        return out;
    }

    private static void locked(List<String> out, String sp, String ip, String lock, String kind, String content) {
        out.add(sp + lock + "." + kind + "Lock().lock();");
        out.add(sp + "try {");
        addIfPresent(out, content);
        out.add(sp + "} finally {");
        out.add(ip + lock + "." + kind + "Lock().unlock();");
        out.add(sp + "}");
    }

    private static void addIfPresent(List<String> out, String content) {
        if (!content.isEmpty()) out.add(content);
    }

    /**
     * Replacement for one write branch of the condition-checking if: release the read lock, take the
     * write lock, run the whole if again (the condition may have changed meanwhile, see line 47 of
     * Figure 1(c)), then downgrade. The read lock is re-acquired in {@code finally} before the write
     * lock is released, so the caller always holds the read lock afterwards, also when the
     * re-check fails or an exception is thrown.
     */
    private String upgradeBlock(SourceFile f, IfStmt top, Statement branch, String lock) {
        String nl = f.lineSep;
        String u = f.indentUnit;
        String l = f.indentAt(f.start(branch));
        String copy = f.reindent(f.text(f.start(top), f.end(top)), f.indentAt(f.start(top)), l + u + u);
        return "{" + nl
                + l + u + lock + ".readLock().unlock();" + nl
                + l + u + lock + ".writeLock().lock();" + nl
                + l + u + "try {" + nl
                + copy + nl
                + l + u + "} finally {" + nl
                + l + u + u + lock + ".readLock().lock();" + nl
                + l + u + u + lock + ".writeLock().unlock();" + nl
                + l + u + "}" + nl
                + l + "}";
    }

    private static String content(SourceFile f, int start, int end, List<SourceFile.Edit> extra, String to) {
        String raw = f.text(start, end, extra);
        if (raw.isBlank()) return "";
        return f.reindent(raw, f.indentOfFirstCode(start, end), to);
    }

    /** Expression that reaches the group's lock field from inside section {@code s}. */
    private static String lockAccess(LockGroup g, CriticalSection s) {
        String name = g.lockField;
        MonitorKey k = s.monitor;
        String here = s.enclosingType;
        return switch (k.kind()) {
            case THIS -> here.equals(k.typeName()) ? name : MonitorKey.simple(k.typeName()) + ".this." + name;
            case CLASS -> here.equals(g.owner) ? name : MonitorKey.simple(g.owner) + "." + name;
            case FIELD -> here.equals(k.typeName()) ? name
                    : MonitorKey.simple(k.typeName()) + (k.isStatic() ? "." : ".this.") + name;
        };
    }

    // ------------------------------------------------------------------ lock field and import

    private String chooseFieldName(LockGroup g) {
        MonitorKey k = g.key;
        String base = switch (k.kind()) {
            case THIS -> opts.instanceLockName;
            case CLASS -> opts.staticLockName;
            case FIELD -> k.isStatic() ? upperSnake(k.fieldName()) + "_RW_LOCK" : k.fieldName() + "RwLock";
        };
        Set<String> used = new HashSet<>();
        Set<SourceFile> files = new HashSet<>();
        for (CriticalSection s : g.sections) files.add(s.file);
        TypeDeclaration<?> owner = index.types.get(g.owner);
        files.add(index.typeFile.get(owner));
        for (String t : index.subtypesOf(g.owner)) files.add(index.typeFile.get(index.types.get(t)));
        for (SourceFile f : files) used.addAll(names(f));
        String name = base;
        for (int i = 2; used.contains(name); i++) name = base + i;
        for (SourceFile f : files) names(f).add(name);
        return name;
    }

    private Set<String> names(SourceFile f) {
        return reservedNames.computeIfAbsent(f, k -> {
            Set<String> s = new HashSet<>();
            for (SimpleName n : f.cu.findAll(SimpleName.class)) s.add(n.getIdentifier());
            return s;
        });
    }

    private String uniqueLocal(SourceFile f, String base) {
        Set<String> used = names(f);
        String name = base;
        for (int i = 2; used.contains(name); i++) name = base + i;
        used.add(name);
        return name;
    }

    private static String upperSnake(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
    }

    private void insertField(LockGroup g) {
        TypeDeclaration<?> td = index.types.get(g.owner);
        SourceFile f = index.typeFile.get(td);
        String nl = f.lineSep;
        String type = lockType(f);
        String mods = switch (g.key.kind()) {
            case THIS -> (g.types.size() > 1 ? "protected" : "private") + " final ";
            case CLASS -> "private static final ";
            case FIELD -> "private " + (g.key.isStatic() ? "static " : "") + "final ";
        };
        String decl = mods + type + " " + g.lockField + " = new " + type + "(" + (opts.fairLocks ? "true" : "") + ");";
        String classIndent = f.indentAt(f.start(td.getName()));
        String mi = classIndent + f.indentUnit;

        if (td instanceof EnumDeclaration ed && !ed.getEntries().isEmpty()) {
            if (!ed.getMembers().isEmpty()) {
                BodyDeclaration<?> first = ed.getMembers().get(0);
                int pos = first.getComment().map(f::start).orElse(f.start(first));
                int lineStart = pos - f.indentAt(pos).length();
                f.addEdit(new SourceFile.Edit(lineStart, lineStart, mi + decl + nl + nl));
            } else {
                JavaToken t = ed.getEntries().getLast().get().getTokenRange().orElseThrow().getEnd();
                int at = f.end(ed.getEntries().getLast().get());
                String prefix = ";";
                for (JavaToken n = t.getNextToken().orElse(null); n != null; n = n.getNextToken().orElse(null)) {
                    if (n.getCategory().isWhitespaceOrComment()) continue;
                    if (n.getKind() == JavaToken.Kind.COMMA.getKind()) {
                        at = f.offset(n.getRange().orElseThrow().end) + 1;
                        continue;
                    }
                    if (n.getKind() == JavaToken.Kind.SEMICOLON.getKind()) {
                        at = f.offset(n.getRange().orElseThrow().end) + 1;
                        prefix = "";
                    }
                    break;
                }
                f.addEdit(new SourceFile.Edit(at, at, prefix + nl + nl + mi + decl));
            }
            return;
        }
        for (JavaToken t = td.getName().getTokenRange().orElseThrow().getEnd(); t != null; t = t.getNextToken().orElse(null)) {
            if (t.getKind() == JavaToken.Kind.LBRACE.getKind()) {
                int at = f.offset(t.getRange().orElseThrow().end) + 1;
                int next = at;
                while (next < f.text.length() && Character.isWhitespace(f.text.charAt(next))) next++;
                boolean emptyBody = next < f.text.length() && f.text.charAt(next) == '}';
                String text = nl + mi + decl + (emptyBody ? nl + classIndent : nl);
                if (emptyBody) {
                    f.addEdit(new SourceFile.Edit(at, next, text));
                } else {
                    f.addEdit(new SourceFile.Edit(at, at, text));
                }
                return;
            }
        }
        throw new IllegalStateException("cannot find the body of " + g.owner);
    }

    /** Simple name if an import can be used, else the qualified name. Adds the import when needed. */
    private String lockType(SourceFile f) {
        return lockTypeName.computeIfAbsent(f, k -> {
            boolean conflict = false;
            for (ImportDeclaration id : f.cu.getImports()) {
                if (id.isStatic()) continue;
                String n = id.getNameAsString();
                if (id.isAsterisk() && n.equals("java.util.concurrent.locks")) return LOCK_SIMPLE;
                if (!id.isAsterisk() && n.equals(LOCK_CLASS)) return LOCK_SIMPLE;
                if (!id.isAsterisk() && n.endsWith("." + LOCK_SIMPLE)) conflict = true;
            }
            for (TypeDeclaration<?> td : f.cu.findAll(TypeDeclaration.class)) {
                if (td.getNameAsString().equals(LOCK_SIMPLE)) conflict = true;
            }
            if (conflict) return LOCK_CLASS;
            String nl = f.lineSep;
            String imp = "import " + LOCK_CLASS + ";";
            if (!f.cu.getImports().isEmpty()) {
                int at = f.end(f.cu.getImports().getLast().get());
                f.addEdit(new SourceFile.Edit(at, at, nl + imp));
            } else if (f.cu.getPackageDeclaration().isPresent()) {
                int at = f.end(f.cu.getPackageDeclaration().get());
                f.addEdit(new SourceFile.Edit(at, at, nl + nl + imp));
            } else {
                f.addEdit(new SourceFile.Edit(0, 0, imp + nl + nl));
            }
            return LOCK_SIMPLE;
        });
    }
}
