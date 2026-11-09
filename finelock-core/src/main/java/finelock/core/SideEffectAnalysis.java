package finelock.core;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.PatternExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Side-effect analysis behind the Read-Write Identifier (Section 3.1).
 *
 * <p>A statement is a write if it
 * <ol>
 *   <li>assigns a field, a static variable or an array element (locals and parameters are thread-local),</li>
 *   <li>calls a project method whose body (or the body of any overriding method) writes, transitively, or</li>
 *   <li>calls a library method that is not known to be read-only (see {@link LibraryModel}), or a call
 *       that cannot be resolved.</li>
 * </ol>
 * Effects of project methods are computed for the whole project at once as a least fixpoint, so
 * recursion and mutual recursion are handled.
 */
final class SideEffectAnalysis {

    private static final Set<String> MONITOR_METHODS = Set.of("wait", "notify", "notifyAll");
    /** Read-only for shared state, but they advance an iterator: evaluating them twice differs. */
    private static final Set<String> ADVANCING = Set.of("next", "previous", "nextElement");
    private static final Set<String> BUILDER_CHAIN = Set.of("append", "insert", "reverse", "delete",
            "deleteCharAt", "replace", "setLength", "add", "setCharAt");

    private record CallTarget(String type, String key, String signature, boolean isStatic, String name) {}

    private static final CallTarget UNRESOLVED = new CallTarget(null, null, null, false, null);

    private final ProjectIndex index;
    private final LibraryModel lib;
    private final Map<String, Boolean> callableWrites = new HashMap<>();
    private final Map<Node, CallTarget> callCache = new IdentityHashMap<>();
    private final Map<Node, Boolean> targetCache = new IdentityHashMap<>();

    SideEffectAnalysis(ProjectIndex index, LibraryModel lib) {
        this.index = index;
        this.lib = lib;
    }

    /** Computes, for every project method and constructor, whether it may write shared state. */
    void computeFixpoint() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ProjectIndex.Callable c : index.callables.values()) {
                if (!c.hasBody() || callableWrites.getOrDefault(c.key(), false)) continue;
                Node body = c.decl() instanceof MethodDeclaration md ? md.getBody().orElse(null)
                        : ((ConstructorDeclaration) c.decl()).getBody();
                if (body != null && writes(body, false)) {
                    callableWrites.put(c.key(), true);
                    changed = true;
                }
            }
        }
    }

    boolean callableWrites(String key) {
        return callableWrites.getOrDefault(key, false);
    }

    /** True if executing {@code n} may write shared state. */
    boolean writes(Node n) {
        return writes(n, false);
    }

    /**
     * True if {@code e} can be evaluated twice without any visible difference: it writes no shared
     * state and does not even change locals. FineLock re-evaluates if conditions after upgrading
     * from the read lock to the write lock, so this is required for them.
     */
    boolean isPure(Expression e) {
        if (e.findFirst(AssignExpr.class).isPresent()) return false;
        for (UnaryExpr u : e.findAll(UnaryExpr.class)) {
            if (isIncDec(u)) return false;
        }
        return !writes(e, true);
    }

    private boolean writes(Node n, boolean strict) {
        if (n instanceof Comment || n instanceof LambdaExpr || n instanceof MethodReferenceExpr
                || n instanceof LocalClassDeclarationStmt || n instanceof LocalRecordDeclarationStmt
                || n instanceof TypeDeclaration<?>) {
            return false; // declared here, not executed here
        }
        if (n instanceof AssignExpr a) {
            return targetWrites(a.getTarget()) || subWrites(a.getTarget(), strict) || writes(a.getValue(), strict);
        }
        if (n instanceof UnaryExpr u && isIncDec(u)) {
            return targetWrites(u.getExpression()) || subWrites(u.getExpression(), strict);
        }
        if (n instanceof MethodCallExpr mc) {
            if (callWrites(mc, strict)) return true;
        } else if (n instanceof ObjectCreationExpr oc) {
            if (constructorWrites(oc)) return true;
            if (oc.getScope().isPresent() && writes(oc.getScope().get(), strict)) return true;
            for (Expression arg : oc.getArguments()) if (writes(arg, strict)) return true;
            return false; // do not descend into an anonymous class body
        } else if (n instanceof ExplicitConstructorInvocationStmt ec) {
            if (explicitConstructorWrites(ec)) return true;
        }
        for (Node child : n.getChildNodes()) {
            if (writes(child, strict)) return true;
        }
        return false;
    }

    private static boolean isIncDec(UnaryExpr u) {
        return switch (u.getOperator()) {
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
            default -> false;
        };
    }

    /** Effects of evaluating the parts of an assignment target (array index, field scope ...). */
    private boolean subWrites(Expression target, boolean strict) {
        Expression t = unwrap(target);
        if (t instanceof FieldAccessExpr fa) return writes(fa.getScope(), strict);
        if (t.isArrayAccessExpr()) {
            return writes(t.asArrayAccessExpr().getName(), strict) || writes(t.asArrayAccessExpr().getIndex(), strict);
        }
        return false;
    }

    /** Does assigning to {@code target} write shared state? */
    private boolean targetWrites(Expression target) {
        return targetCache.computeIfAbsent(target, k -> computeTargetWrites(unwrap(target)));
    }

    private boolean computeTargetWrites(Expression t) {
        if (t instanceof NameExpr ne) {
            try {
                ResolvedValueDeclaration d = ne.resolve();
                if (d.isField()) {
                    boolean isStatic = d.asField().isStatic();
                    String owner = d.asField().declaringType().getQualifiedName();
                    return !constructorOwnField(ne, isStatic, owner);
                }
                return false; // local variable, parameter or pattern binding
            } catch (RuntimeException e) {
                return !isLocalName(ne, ne.getNameAsString());
            }
        }
        if (t instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr th && th.getTypeName().isEmpty()) {
            boolean isStatic = false;
            String owner = null;
            try {
                ResolvedValueDeclaration d = fa.resolve();
                if (d.isField()) {
                    isStatic = d.asField().isStatic();
                    owner = d.asField().declaringType().getQualifiedName();
                }
            } catch (RuntimeException ignored) {
                // assume an instance field of this class
            }
            return !constructorOwnField(fa, isStatic, owner);
        }
        return true; // other fields, array elements
    }

    /** Writing a non-static field of the object under construction is not a shared write. */
    private boolean constructorOwnField(Node n, boolean isStatic, String fieldOwner) {
        if (isStatic) return false;
        Node callable = enclosingCallable(n);
        if (!(callable instanceof ConstructorDeclaration || callable instanceof InitializerDeclaration id && !id.isStatic())) {
            return false;
        }
        String ctorOwner = ProjectIndex.enclosingTypeName(callable).orElse(null);
        return ctorOwner != null && (fieldOwner == null || ctorOwner.equals(fieldOwner));
    }

    private static Node enclosingCallable(Node n) {
        Node cur = n.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof CallableDeclaration<?> || cur instanceof LambdaExpr
                    || cur instanceof InitializerDeclaration || cur instanceof TypeDeclaration<?>) {
                return cur;
            }
            cur = cur.getParentNode().orElse(null);
        }
        return null;
    }

    /** Syntactic fallback: is {@code name} declared as a local, parameter or binding around {@code n}? */
    static boolean isLocalName(Node n, String name) {
        Node cur = n.getParentNode().orElse(null);
        while (cur != null && !(cur instanceof TypeDeclaration<?>)) {
            if (cur instanceof CallableDeclaration<?> cd) {
                for (Parameter p : cd.getParameters()) if (p.getNameAsString().equals(name)) return true;
            }
            if (cur instanceof LambdaExpr le) {
                for (Parameter p : le.getParameters()) if (p.getNameAsString().equals(name)) return true;
            }
            if (cur instanceof CallableDeclaration<?> || cur instanceof LambdaExpr || cur instanceof InitializerDeclaration) {
                if (declaresLocal(cur, name)) return true;
            }
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean declaresLocal(Node scope, String name) {
        for (VariableDeclarator v : scope.findAll(VariableDeclarator.class)) {
            if (v.getNameAsString().equals(name) && !(v.getParentNode().orElse(null) instanceof com.github.javaparser.ast.body.FieldDeclaration)) {
                return true;
            }
        }
        for (CatchClause c : scope.findAll(CatchClause.class)) {
            if (c.getParameter().getNameAsString().equals(name)) return true;
        }
        for (TypePatternExpr p : scope.findAll(TypePatternExpr.class)) {
            if (p.getNameAsString().equals(name)) return true;
        }
        return false;
    }

    private boolean callWrites(MethodCallExpr mc, boolean strict) {
        String name = mc.getNameAsString();
        if (MONITOR_METHODS.contains(name) && mc.getArguments().size() <= 2
                && (mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr)) {
            return false; // sections using wait/notify are excluded by the lockset analysis
        }
        CallTarget t = callCache.computeIfAbsent(mc, k -> resolveCall(mc));
        if (t == UNRESOLVED) return true;
        if (index.isProjectType(t.type)) {
            List<ProjectIndex.Callable> targets = index.dispatchTargets(t.key, t.type, t.signature, t.isStatic);
            if (targets.isEmpty()) return !isImplicitReadOnly(t, mc);
            for (ProjectIndex.Callable c : targets) {
                if (callableWrites(c.key())) return true;
            }
            return false;
        }
        if (strict && ADVANCING.contains(name)) return true;
        boolean readOnly = lib.isReadOnly(t.type, name) || (!strict && freshReceiver(mc, t.type));
        if (!readOnly) return true;
        return functionalArgsWrite(mc.getArguments());
    }

    /** Members the compiler generates: enum values()/valueOf()/name(), record accessors, Object methods. */
    private boolean isImplicitReadOnly(CallTarget t, MethodCallExpr mc) {
        if (index.callables.containsKey(t.key)) return false; // declared but abstract with no known implementation
        TypeDeclaration<?> td = index.types.get(t.type);
        if (td == null || !td.getMethodsByName(t.name).isEmpty()) return false; // declared, but key mismatch
        if (td instanceof RecordDeclaration rd && mc.getArguments().isEmpty()) {
            return rd.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(t.name))
                    || lib.isReadOnly("java.lang.Object", t.name);
        }
        if (td.isEnumDeclaration()) {
            return Set.of("values", "valueOf", "name", "ordinal", "compareTo", "getDeclaringClass",
                    "equals", "hashCode", "toString").contains(t.name);
        }
        return false;
    }

    private CallTarget resolveCall(MethodCallExpr mc) {
        try {
            ResolvedMethodDeclaration m = mc.resolve();
            return new CallTarget(m.declaringType().getQualifiedName(), m.getQualifiedSignature(), m.getSignature(),
                    m.isStatic(), m.getName());
        } catch (RuntimeException | StackOverflowError e) {
            return UNRESOLVED;
        }
    }

    private boolean functionalArgsWrite(List<Expression> args) {
        for (Expression a : args) {
            Expression arg = unwrap(a);
            if (arg instanceof LambdaExpr le) {
                if (writes(le.getBody(), false)) return true;
            } else if (arg instanceof MethodReferenceExpr mr) {
                if (methodReferenceWrites(mr)) return true;
            }
        }
        return false;
    }

    private boolean methodReferenceWrites(MethodReferenceExpr mr) {
        try {
            ResolvedMethodDeclaration m = mr.resolve();
            String type = m.declaringType().getQualifiedName();
            if (index.isProjectType(type)) {
                List<ProjectIndex.Callable> targets = index.dispatchTargets(m.getQualifiedSignature(), type, m.getSignature(), m.isStatic());
                if (targets.isEmpty()) return true;
                for (ProjectIndex.Callable c : targets) if (callableWrites(c.key())) return true;
                return false;
            }
            return !lib.isReadOnly(type, m.getName());
        } catch (RuntimeException | StackOverflowError e) {
            return true;
        }
    }

    /** A call on a collection/builder object that the current method created itself. */
    private boolean freshReceiver(MethodCallExpr mc, String type) {
        if (!lib.isLocalObjectType(type) || mc.getScope().isEmpty()) return false;
        return isFresh(mc.getScope().get(), mc);
    }

    private boolean isFresh(Expression e, Node context) {
        Expression x = unwrap(e);
        if (x instanceof ObjectCreationExpr oc) return oc.getAnonymousClassBody().isEmpty();
        if (x instanceof MethodCallExpr inner && BUILDER_CHAIN.contains(inner.getNameAsString()) && inner.getScope().isPresent()) {
            CallTarget t = callCache.computeIfAbsent(inner, k -> resolveCall(inner));
            return t != UNRESOLVED && lib.isLocalObjectType(t.type) && isFresh(inner.getScope().get(), context);
        }
        if (x instanceof NameExpr ne) {
            Node callable = enclosingCallable(context);
            if (callable == null || callable instanceof TypeDeclaration<?>) return false;
            String name = ne.getNameAsString();
            List<VariableDeclarator> decls = callable.findAll(VariableDeclarator.class,
                    v -> v.getNameAsString().equals(name));
            if (decls.size() != 1) return false;
            Expression init = decls.get(0).getInitializer().map(SideEffectAnalysis::unwrap).orElse(null);
            boolean freshInit = (init instanceof ObjectCreationExpr oc && oc.getAnonymousClassBody().isEmpty())
                    || init instanceof ArrayCreationExpr;
            if (!freshInit) return false;
            return callable.findAll(AssignExpr.class, a -> unwrap(a.getTarget()) instanceof NameExpr t
                    && t.getNameAsString().equals(name)).isEmpty();
        }
        return false;
    }

    private boolean constructorWrites(ObjectCreationExpr oc) {
        if (oc.getAnonymousClassBody().isPresent()) return false;
        CallTarget t = callCache.computeIfAbsent(oc, k -> {
            try {
                ResolvedConstructorDeclaration c = oc.resolve();
                return new CallTarget(c.declaringType().getQualifiedName(), c.getQualifiedSignature(), c.getSignature(), true, "<init>");
            } catch (RuntimeException | StackOverflowError e) {
                return UNRESOLVED;
            }
        });
        if (t == UNRESOLVED) {
            try {
                String type = oc.getType().resolve().asReferenceType().getQualifiedName();
                return index.isProjectType(type);
            } catch (RuntimeException | StackOverflowError e) {
                return true;
            }
        }
        if (!index.isProjectType(t.type)) return false; // library constructors only initialise the new object
        return callableWrites(t.key);
    }

    private boolean explicitConstructorWrites(ExplicitConstructorInvocationStmt ec) {
        CallTarget t = callCache.computeIfAbsent(ec, k -> {
            try {
                ResolvedConstructorDeclaration c = ec.resolve();
                return new CallTarget(c.declaringType().getQualifiedName(), c.getQualifiedSignature(), c.getSignature(), true, "<init>");
            } catch (RuntimeException | StackOverflowError e) {
                return UNRESOLVED;
            }
        });
        if (t == UNRESOLVED) return true;
        return index.isProjectType(t.type) && callableWrites(t.key);
    }

    static Expression unwrap(Expression e) {
        while (e instanceof EnclosedExpr en) e = en.getInner();
        return e;
    }

    /** Names that {@code n} declares or assigns (locals included). */
    static Set<String> namesWritten(Node n) {
        Set<String> out = new java.util.HashSet<>();
        for (VariableDeclarator v : n.findAll(VariableDeclarator.class)) out.add(v.getNameAsString());
        for (PatternExpr p : n.findAll(PatternExpr.class)) {
            if (p instanceof TypePatternExpr tp) out.add(tp.getNameAsString());
        }
        for (AssignExpr a : n.findAll(AssignExpr.class)) {
            if (unwrap(a.getTarget()) instanceof NameExpr ne) out.add(ne.getNameAsString());
        }
        for (UnaryExpr u : n.findAll(UnaryExpr.class)) {
            if (isIncDec(u) && unwrap(u.getExpression()) instanceof NameExpr ne) out.add(ne.getNameAsString());
        }
        return out;
    }

    /** Simple names that {@code n} reads. */
    static Set<String> namesRead(Node n) {
        Set<String> out = new java.util.HashSet<>();
        for (NameExpr ne : n.findAll(NameExpr.class)) out.add(ne.getNameAsString());
        return out;
    }
}
