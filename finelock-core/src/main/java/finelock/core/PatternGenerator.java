package finelock.core;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.List;

/**
 * Pattern Generator (Section 3.1): encodes a statement sequence as a string over {r, w, c, e}.
 * An if whose condition only reads becomes {@code c <then> <else> e}; an if whose condition writes is
 * a single {@code w}. Every other statement is a single {@code r} or {@code w}.
 */
final class PatternGenerator {

    private final SideEffectAnalysis effects;

    PatternGenerator(SideEffectAnalysis effects) {
        this.effects = effects;
    }

    String of(List<Statement> stmts) {
        StringBuilder sb = new StringBuilder();
        for (Statement s : stmts) sb.append(of(s));
        return sb.toString();
    }

    String of(Statement s) {
        if (s instanceof EmptyStmt) return "";
        if (s instanceof BlockStmt b) return of(b.getStatements());
        if (s instanceof IfStmt is) {
            if (effects.writes(is.getCondition())) return "w";
            StringBuilder sb = new StringBuilder("c");
            sb.append(of(is.getThenStmt()));
            is.getElseStmt().ifPresent(e -> sb.append(of(e)));
            return sb.append('e').toString();
        }
        return effects.writes(s) ? "w" : "r";
    }
}
