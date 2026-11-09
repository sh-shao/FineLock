package finelock.core;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lockset Analysis (Section 3.1): maps every monitor to one {@code ReentrantReadWriteLock}.
 *
 * <p>The paper used WALA for alias analysis. FineLock 2 only accepts monitors whose aliasing is
 * clear from the source: {@code this}, {@code X.class}, and final fields initialised with a new
 * object. {@code this} of a class and of its superclass is the same object, so such sections are
 * merged into one group whose lock lives in the topmost class.
 */
final class LocksetAnalysis {

    private final ProjectIndex index;
    final Map<MonitorKey, LockGroup> groups = new LinkedHashMap<>();
    private final Map<MonitorKey, MonitorKey> parent = new HashMap<>();

    LocksetAnalysis(ProjectIndex index) {
        this.index = index;
    }

    void run(List<CriticalSection> sections, List<LockLocator.Exclusion> exclusions) {
        for (CriticalSection s : sections) {
            if (s.monitor != null) parent.putIfAbsent(s.monitor, s.monitor);
        }
        // this of a class and this of its superclass are the same object
        for (MonitorKey k : new ArrayList<>(parent.keySet())) {
            if (k.kind() != MonitorKey.Kind.THIS) continue;
            for (String sup = index.projectSuperclass.get(k.typeName()); sup != null; sup = index.projectSuperclass.get(sup)) {
                MonitorKey sk = MonitorKey.ofThis(sup);
                if (parent.containsKey(sk)) union(k, sk);
            }
        }
        for (CriticalSection s : sections) {
            if (s.monitor == null) continue;
            MonitorKey root = find(s.monitor);
            LockGroup g = groups.computeIfAbsent(root, LockGroup::new);
            g.sections.add(s);
            if (s.monitor.kind() == MonitorKey.Kind.THIS) g.types.add(s.monitor.typeName());
            s.group = g;
        }
        for (LockGroup g : groups.values()) {
            chooseOwner(g);
        }
        for (LockLocator.Exclusion ex : exclusions) {
            for (LockGroup g : groupsFor(ex.key())) g.exclude(ex.reason());
        }
        for (LockGroup g : groups.values()) {
            mergeNested(g);
        }
    }

    /** Groups an excluded key may belong to: for this-monitors, the class itself or any superclass. */
    private List<LockGroup> groupsFor(MonitorKey key) {
        List<LockGroup> out = new ArrayList<>();
        if (key.kind() == MonitorKey.Kind.THIS) {
            for (String t = key.typeName(); t != null; t = index.projectSuperclass.get(t)) {
                MonitorKey k = MonitorKey.ofThis(t);
                if (parent.containsKey(k)) out.add(groups.get(find(k)));
            }
        } else if (parent.containsKey(key)) {
            out.add(groups.get(find(key)));
        }
        out.removeIf(java.util.Objects::isNull);
        return out;
    }

    private void chooseOwner(LockGroup g) {
        MonitorKey k = g.key;
        String owner = k.typeName();
        if (k.kind() == MonitorKey.Kind.THIS) {
            // topmost class of the group
            for (String t : g.types) {
                boolean hasAncestorInGroup = false;
                for (String sup = index.projectSuperclass.get(t); sup != null; sup = index.projectSuperclass.get(sup)) {
                    if (g.types.contains(sup)) hasAncestorInGroup = true;
                }
                if (!hasAncestorInGroup) owner = t;
            }
        }
        g.owner = owner;
        TypeDeclaration<?> td = index.types.get(owner);
        if (td == null) {
            g.exclude("cannot find class " + owner);
        } else if (td.isAnnotationDeclaration() || (td.isClassOrInterfaceDeclaration() && td.asClassOrInterfaceDeclaration().isInterface())) {
            g.exclude("the lock field would have to be declared in an interface");
        } else if (td instanceof RecordDeclaration && !(k.kind() == MonitorKey.Kind.CLASS || k.isStatic())) {
            g.exclude("records cannot declare instance fields");
        }
    }

    /**
     * A synchronized block nested directly inside another section of the same group is reentrant
     * and adds nothing; it is merged into the enclosing section.
     */
    private void mergeNested(LockGroup g) {
        List<CriticalSection> sorted = new ArrayList<>(g.sections);
        sorted.sort(Comparator.comparingInt(CriticalSection::length));
        for (CriticalSection inner : sorted) {
            if (!(inner.node instanceof SynchronizedStmt)) continue;
            for (CriticalSection outer : sorted) {
                if (outer != inner && outer.file == inner.file && executesWithin(inner.node, outer.node)) {
                    // pick the outermost enclosing one
                    if (inner.mergedInto == null || inner.mergedInto.length() < outer.length()) inner.mergedInto = outer;
                }
            }
        }
    }

    /** True if {@code inner} runs while {@code outer} is executing, i.e. not inside a lambda or class body in between. */
    static boolean executesWithin(Node inner, Node outer) {
        Node cur = inner.getParentNode().orElse(null);
        while (cur != null) {
            if (cur == outer) return true;
            if (cur instanceof LambdaExpr || cur instanceof TypeDeclaration<?>
                    || (cur instanceof ObjectCreationExpr oc && oc.getAnonymousClassBody().isPresent())) {
                return false;
            }
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private MonitorKey find(MonitorKey k) {
        MonitorKey p = parent.get(k);
        if (p.equals(k)) return k;
        MonitorKey r = find(p);
        parent.put(k, r);
        return r;
    }

    private void union(MonitorKey a, MonitorKey b) {
        MonitorKey ra = find(a), rb = find(b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }
}
