package finelock.core;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Lock Locator (Section 3.1): finds every synchronized method and block and resolves its monitor.
 * It also records uses of monitors that FineLock cannot convert (wait/notify, Thread.holdsLock,
 * synchronizing on the same object through another expression); the lockset analysis excludes
 * the affected groups.
 */
final class LockLocator {

    record Exclusion(MonitorKey key, String reason) {}

    private record Resolution(MonitorKey key, String unsupported, List<Exclusion> exclusions) {
        static Resolution ok(MonitorKey k) {
            return new Resolution(k, null, List.of());
        }

        static Resolution no(String why, List<Exclusion> ex) {
            return new Resolution(null, why, ex);
        }
    }

    private static final Set<String> MONITOR_METHODS = Set.of("wait", "notify", "notifyAll");

    private final ProjectIndex index;
    final List<CriticalSection> sections = new ArrayList<>();
    final List<Exclusion> exclusions = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    LockLocator(ProjectIndex index) {
        this.index = index;
    }

    void run() {
        for (SourceFile f : index.files) {
            for (MethodDeclaration md : f.cu.findAll(MethodDeclaration.class)) {
                if (!md.isSynchronized()) continue;
                String owner = ProjectIndex.enclosingTypeName(md).orElse(null);
                if (md.getBody().isEmpty()) {
                    // native synchronized method: it uses the monitor and cannot be rewritten
                    if (owner != null) {
                        MonitorKey k = md.isStatic() ? MonitorKey.ofClass(owner) : MonitorKey.ofThis(owner);
                        exclusions.add(new Exclusion(k, "native synchronized method " + md.getNameAsString()));
                    }
                    continue;
                }
                CriticalSection s = new CriticalSection(f, md, md.getBody().get(), owner, memberName(md));
                if (owner == null) {
                    s.unsupportedReason = "synchronized method of an anonymous or local class";
                } else {
                    s.monitor = md.isStatic() ? MonitorKey.ofClass(owner) : MonitorKey.ofThis(owner);
                }
                sections.add(s);
            }
            for (SynchronizedStmt ss : f.cu.findAll(SynchronizedStmt.class)) {
                String owner = ProjectIndex.enclosingTypeName(ss).orElse(null);
                CriticalSection s = new CriticalSection(f, ss, ss.getBody(), owner, memberName(ss));
                Resolution r = resolve(ss.getExpression(), ss);
                s.monitor = r.key;
                s.unsupportedReason = r.unsupported;
                exclusions.addAll(r.exclusions);
                sections.add(s);
            }
            for (MethodCallExpr mc : f.cu.findAll(MethodCallExpr.class)) {
                scanMonitorUse(mc);
            }
        }
    }

    /** wait/notify/notifyAll on a monitor, or Thread.holdsLock(monitor). */
    private void scanMonitorUse(MethodCallExpr mc) {
        String name = mc.getNameAsString();
        Expression monitor;
        String what;
        if (MONITOR_METHODS.contains(name) && mc.getArguments().size() <= 2) {
            if (!declaredByObject(mc)) return;
            monitor = mc.getScope().orElseGet(ThisExpr::new);
            what = name + "()";
        } else if (name.equals("holdsLock") && mc.getArguments().size() == 1
                && mc.getScope().map(s -> s.toString().endsWith("Thread")).orElse(false)) {
            monitor = mc.getArgument(0);
            what = "Thread.holdsLock()";
        } else {
            return;
        }
        Expression m = monitor;
        if (m instanceof ThisExpr && mc.getScope().isEmpty()) {
            // implicit this: the expression is not in the tree, resolve against the call
            String owner = ProjectIndex.enclosingTypeName(mc).orElse(null);
            if (owner == null) return;
            if (isInStaticContext(mc)) return;
            exclusions.add(new Exclusion(MonitorKey.ofThis(owner), what + " on the monitor"));
            return;
        }
        Resolution r = resolve(m, mc);
        if (r.key != null) exclusions.add(new Exclusion(r.key, what + " on the monitor"));
        exclusions.addAll(r.exclusions);
    }

    private static boolean declaredByObject(MethodCallExpr mc) {
        try {
            ResolvedMethodDeclaration m = mc.resolve();
            return m.declaringType().getQualifiedName().equals("java.lang.Object");
        } catch (RuntimeException | StackOverflowError e) {
            return true; // assume the worst
        }
    }

    private static boolean isInStaticContext(Node n) {
        Optional<MethodDeclaration> md = n.findAncestor(MethodDeclaration.class);
        return md.isPresent() && md.get().isStatic();
    }

    /** Resolves a monitor expression to a key, or explains why it is unsupported. */
    private Resolution resolve(Expression expr, Node ctx) {
        Expression e = SideEffectAnalysis.unwrap(expr);
        String enclosing = ProjectIndex.enclosingTypeName(ctx).orElse(null);
        if (e instanceof ThisExpr th) {
            if (th.getTypeName().isEmpty()) {
                if (enclosing == null) return Resolution.no("monitor is an instance of an anonymous or local class", List.of());
                return Resolution.ok(MonitorKey.ofThis(enclosing));
            }
            String simple = th.getTypeName().get().getIdentifier();
            for (TypeDeclaration<?> td : outerTypes(ctx)) {
                if (td.getNameAsString().equals(simple) && td.getFullyQualifiedName().isPresent()) {
                    return Resolution.ok(MonitorKey.ofThis(td.getFullyQualifiedName().get()));
                }
            }
            return Resolution.no("cannot resolve " + e, List.of());
        }
        if (e instanceof ClassExpr ce) {
            try {
                ResolvedType t = ce.getType().resolve();
                if (t.isReferenceType()) {
                    String qn = t.asReferenceType().getQualifiedName();
                    if (index.isProjectType(qn)) {
                        if (enclosing != null && topLevel(qn).equals(topLevel(enclosing))) {
                            return Resolution.ok(MonitorKey.ofClass(qn));
                        }
                        return Resolution.no("synchronizes on " + MonitorKey.simple(qn) + ".class from another class",
                                List.of(new Exclusion(MonitorKey.ofClass(qn), "synchronized on from " + enclosing)));
                    }
                    return Resolution.no("monitor is the Class object of library type " + qn, List.of());
                }
            } catch (RuntimeException | StackOverflowError ignored) {
                // fall through
            }
            return Resolution.no("cannot resolve " + e, List.of());
        }
        if (e instanceof NameExpr || (e instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr)) {
            try {
                ResolvedValueDeclaration d = e instanceof NameExpr ne ? ne.resolve() : e.asFieldAccessExpr().resolve();
                if (d.isField()) {
                    String owner = d.asField().declaringType().getQualifiedName();
                    String name = d.getName();
                    boolean isStatic = d.asField().isStatic();
                    MonitorKey k = MonitorKey.ofField(owner, name, isStatic);
                    if (!index.isProjectType(owner)) {
                        return Resolution.no("monitor is a field of library type " + owner, List.of());
                    }
                    if (!outerTypeNames(ctx).contains(owner)) {
                        return Resolution.no("monitor field " + name + " is inherited from " + MonitorKey.simple(owner),
                                List.of(new Exclusion(k, "synchronized on from subclass " + enclosing)));
                    }
                    String problem = fieldProblem(owner, name);
                    if (problem != null) {
                        return Resolution.no("monitor field " + name + " " + problem, List.of());
                    }
                    return Resolution.ok(k);
                }
                return Resolution.no("monitor is a local variable or parameter", typeExclusions(e, "synchronized on via a local variable or parameter"));
            } catch (RuntimeException | StackOverflowError ex) {
                return Resolution.no("cannot resolve monitor " + e, List.of());
            }
        }
        if (e instanceof FieldAccessExpr fa) {
            List<Exclusion> ex = new ArrayList<>(typeExclusions(e, "synchronized on via " + e));
            try {
                ResolvedValueDeclaration d = fa.resolve();
                if (d.isField()) {
                    ex.add(new Exclusion(MonitorKey.ofField(d.asField().declaringType().getQualifiedName(), d.getName(),
                            d.asField().isStatic()), "synchronized on via " + e));
                }
            } catch (RuntimeException | StackOverflowError ignored) {
                // nothing more to exclude
            }
            return Resolution.no("monitor is a field of another object (" + e + ")", ex);
        }
        if (e instanceof MethodCallExpr mc && mc.getNameAsString().equals("getClass") && mc.getArguments().isEmpty()
                && (mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr) && enclosing != null) {
            List<Exclusion> ex = new ArrayList<>();
            for (String t : index.subtypesOf(enclosing)) ex.add(new Exclusion(MonitorKey.ofClass(t), "synchronized on via getClass()"));
            return Resolution.no("monitor is getClass()", ex);
        }
        return Resolution.no("unsupported monitor expression " + e, typeExclusions(e, "synchronized on via " + e));
    }

    /** If the monitor expression has a project class type, every instance of that class (or a subclass) may be it. */
    private List<Exclusion> typeExclusions(Expression e, String reason) {
        try {
            ResolvedType t = e.calculateResolvedType();
            if (t.isReferenceType()) {
                String qn = t.asReferenceType().getQualifiedName();
                if (index.isProjectType(qn)) {
                    List<Exclusion> out = new ArrayList<>();
                    for (String sub : index.subtypesOf(qn)) out.add(new Exclusion(MonitorKey.ofThis(sub), reason));
                    return out;
                }
                if (qn.equals("java.lang.Object")) {
                    warnings.add("synchronized on an expression of type Object (" + e + " at line "
                            + e.getRange().map(r -> r.begin.line).orElse(0) + "): FineLock cannot tell which monitor it is");
                }
            }
        } catch (RuntimeException | StackOverflowError ignored) {
            // unknown type
        }
        return List.of();
    }

    /** Null if the field is a final field holding a fresh object, otherwise what is wrong with it. */
    private String fieldProblem(String owner, String name) {
        Optional<VariableDeclarator> v = index.field(owner, name);
        if (v.isEmpty()) return "cannot be found";
        Node parent = v.get().getParentNode().orElse(null);
        if (!(parent instanceof com.github.javaparser.ast.body.FieldDeclaration fd) || !fd.hasModifier(Modifier.Keyword.FINAL)) {
            return "is not final, so different threads may lock different objects";
        }
        Expression init = v.get().getInitializer().map(SideEffectAnalysis::unwrap).orElse(null);
        if (!(init instanceof ObjectCreationExpr) && !(init instanceof ArrayCreationExpr)) {
            return "is not initialised with a new object, so the object may be shared with other code";
        }
        return null;
    }

    private static List<TypeDeclaration<?>> outerTypes(Node n) {
        List<TypeDeclaration<?>> out = new ArrayList<>();
        Node cur = n.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof TypeDeclaration<?> td) out.add(td);
            cur = cur.getParentNode().orElse(null);
        }
        return out;
    }

    private static List<String> outerTypeNames(Node n) {
        List<String> out = new ArrayList<>();
        for (TypeDeclaration<?> td : outerTypes(n)) td.getFullyQualifiedName().ifPresent(out::add);
        return out;
    }

    private String topLevel(String qn) {
        TypeDeclaration<?> td = index.types.get(qn);
        if (td == null) return qn;
        Node cur = td;
        TypeDeclaration<?> top = td;
        while (cur != null) {
            if (cur instanceof TypeDeclaration<?> t) top = t;
            cur = cur.getParentNode().orElse(null);
        }
        return top.getFullyQualifiedName().orElse(qn);
    }

    static String memberName(Node n) {
        Optional<com.github.javaparser.ast.body.CallableDeclaration> cd = n instanceof com.github.javaparser.ast.body.CallableDeclaration<?> c
                ? Optional.of(c) : n.findAncestor(com.github.javaparser.ast.body.CallableDeclaration.class);
        if (cd.isPresent()) return cd.get().getDeclarationAsString(false, false, false);
        return n.findAncestor(com.github.javaparser.ast.body.InitializerDeclaration.class).isPresent() ? "<initializer>" : "<field initializer>";
    }
}
