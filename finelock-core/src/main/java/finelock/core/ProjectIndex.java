package finelock.core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** All parsed sources of the project plus lookup tables used by the analyses. */
final class ProjectIndex {

    /** A method or constructor declared in the project. */
    record Callable(String key, String declaringType, String signature, CallableDeclaration<?> decl,
                    SourceFile file, boolean hasBody, boolean isConstructor) {}

    final List<SourceFile> files = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final Map<String, TypeDeclaration<?>> types = new LinkedHashMap<>();
    final Map<TypeDeclaration<?>, SourceFile> typeFile = new HashMap<>();
    final Map<String, Callable> callables = new LinkedHashMap<>();
    final Map<CallableDeclaration<?>, Callable> callableOf = new HashMap<>();
    /** signature (no type) -> callables with that signature, used to find overriding methods. */
    private final Map<String, List<Callable>> bySignature = new HashMap<>();
    /** Project type -> all its ancestors (qualified names), as far as they resolve. */
    private final Map<String, Set<String>> ancestors = new HashMap<>();
    /** Project class -> its direct superclass, if that superclass is a project class. */
    final Map<String, String> projectSuperclass = new HashMap<>();

    ProjectIndex(List<Path> sourceRoots, List<Path> classpath) throws IOException {
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
        ParserConfiguration base = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setTabSize(1);
        for (Path root : sourceRoots) {
            solver.add(new JavaParserTypeSolver(root, new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)));
        }
        for (Path jar : classpath) {
            try {
                if (Files.isRegularFile(jar) && jar.toString().endsWith(".jar")) {
                    solver.add(new JarTypeSolver(jar));
                }
            } catch (IOException | RuntimeException e) {
                warnings.add("cannot read classpath entry " + jar + ": " + e.getMessage());
            }
        }
        ParserConfiguration config = base.setSymbolResolver(new JavaSymbolSolver(solver));
        JavaParser parser = new JavaParser(config);

        Set<Path> seen = new HashSet<>();
        for (Path root : sourceRoots) {
            List<Path> javaFiles;
            try (Stream<Path> s = Files.walk(root)) {
                javaFiles = s.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).sorted().toList();
            }
            for (Path p : javaFiles) {
                Path abs = p.toAbsolutePath().normalize();
                if (!seen.add(abs)) continue;
                String text = Files.readString(p, StandardCharsets.UTF_8);
                ParseResult<CompilationUnit> r = parser.parse(text);
                if (!r.isSuccessful() || r.getResult().isEmpty()) {
                    warnings.add("cannot parse " + abs + ": " + r.getProblems());
                    continue;
                }
                files.add(new SourceFile(abs, text, r.getResult().get()));
            }
        }
        indexTypes();
        indexCallables();
    }

    private void indexTypes() {
        for (SourceFile f : files) {
            for (TypeDeclaration<?> td : f.cu.findAll(TypeDeclaration.class)) {
                Optional<String> qn = td.getFullyQualifiedName();
                if (qn.isEmpty()) continue; // local classes
                types.put(qn.get(), td);
                typeFile.put(td, f);
            }
        }
        for (Map.Entry<String, TypeDeclaration<?>> e : types.entrySet()) {
            Set<String> anc = new HashSet<>();
            try {
                for (ResolvedReferenceType t : e.getValue().resolve().getAllAncestors()) {
                    anc.add(t.getQualifiedName());
                }
            } catch (RuntimeException ex) {
                // partially unresolvable hierarchy: keep what we can see syntactically
            }
            if (e.getValue() instanceof ClassOrInterfaceDeclaration cd && !cd.isInterface()) {
                for (ClassOrInterfaceType ext : cd.getExtendedTypes()) {
                    try {
                        String sup = ext.resolve().asReferenceType().getQualifiedName();
                        anc.add(sup);
                        if (types.containsKey(sup)) projectSuperclass.put(e.getKey(), sup);
                    } catch (RuntimeException ex) {
                        warnings.add("cannot resolve superclass " + ext + " of " + e.getKey());
                    }
                }
            }
            ancestors.put(e.getKey(), anc);
        }
    }

    private void indexCallables() {
        for (SourceFile f : files) {
            for (CallableDeclaration<?> cd : f.cu.findAll(CallableDeclaration.class)) {
                Optional<String> owner = enclosingTypeName(cd);
                if (owner.isEmpty()) continue;
                String sig;
                String key;
                try {
                    ResolvedMethodLikeDeclaration r = cd instanceof MethodDeclaration md ? md.resolve()
                            : ((ConstructorDeclaration) cd).resolve();
                    sig = r.getSignature();
                    key = r.getQualifiedSignature();
                } catch (RuntimeException e) {
                    sig = cd.getSignature().asString();
                    key = owner.get() + "." + sig;
                }
                boolean hasBody = cd instanceof ConstructorDeclaration
                        || ((MethodDeclaration) cd).getBody().isPresent();
                Callable c = new Callable(key, owner.get(), sig, cd, f, hasBody, cd instanceof ConstructorDeclaration);
                callables.put(key, c);
                callableOf.put(cd, c);
                if (!c.isConstructor) bySignature.computeIfAbsent(sig, k -> new ArrayList<>()).add(c);
            }
        }
    }

    /** Qualified name of the class directly declaring {@code n}, if it is a named (non-local, non-anonymous) class. */
    static Optional<String> enclosingTypeName(Node n) {
        Node cur = n.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof ObjectCreationExpr oc && oc.getAnonymousClassBody().isPresent()
                    && isInsideAnonymousBody(n, oc)) {
                return Optional.empty();
            }
            if (cur instanceof TypeDeclaration<?> td) {
                return td.getFullyQualifiedName();
            }
            cur = cur.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    private static boolean isInsideAnonymousBody(Node n, ObjectCreationExpr oc) {
        for (BodyDeclaration<?> b : oc.getAnonymousClassBody().get()) {
            if (b == n || n.isDescendantOf(b)) return true;
        }
        return false;
    }

    /** The named type declaration that directly encloses {@code n}, or empty for anonymous/local classes. */
    Optional<TypeDeclaration<?>> enclosingType(Node n) {
        return enclosingTypeName(n).map(types::get);
    }

    boolean isProjectType(String qn) {
        return types.containsKey(qn);
    }

    boolean isSubtypeOrSame(String type, String ancestor) {
        return type.equals(ancestor) || ancestors.getOrDefault(type, Set.of()).contains(ancestor);
    }

    /** All project types that are {@code type} or a subtype of it. */
    List<String> subtypesOf(String type) {
        List<String> out = new ArrayList<>();
        for (String t : types.keySet()) if (isSubtypeOrSame(t, type)) out.add(t);
        return out;
    }

    /**
     * The project callables a call to {@code key} may dispatch to: the method itself (if it has a
     * body) plus every overriding method in project subtypes. Empty means "unknown".
     */
    List<Callable> dispatchTargets(String key, String declaringType, String signature, boolean isStaticOrCtor) {
        List<Callable> out = new ArrayList<>();
        Callable self = callables.get(key);
        if (self != null && self.hasBody) out.add(self);
        if (!isStaticOrCtor) {
            for (Callable c : bySignature.getOrDefault(signature, List.of())) {
                if (c != self && c.hasBody && !c.declaringType.equals(declaringType)
                        && isSubtypeOrSame(c.declaringType, declaringType)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** Finds the field declarator {@code name} declared directly in project type {@code type}. */
    Optional<VariableDeclarator> field(String type, String name) {
        TypeDeclaration<?> td = types.get(type);
        if (td == null) return Optional.empty();
        for (FieldDeclaration fd : td.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                if (v.getNameAsString().equals(name)) return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    static String qualifiedName(ResolvedReferenceTypeDeclaration t) {
        return t.getQualifiedName();
    }
}
