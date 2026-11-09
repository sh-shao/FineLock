package finelock.core;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;

/** A synchronized method or a synchronized block. */
final class CriticalSection {

    final SourceFile file;
    final Node node;
    final BlockStmt body;
    /** Qualified name of the named class that directly encloses the section, or null. */
    final String enclosingType;
    final String member;

    MonitorKey monitor;
    String unsupportedReason;
    LockGroup group;
    /** Set when this block is nested in another section of the same group and is merged into it. */
    CriticalSection mergedInto;

    String pattern;
    PushdownAutomaton.Result automaton;
    LockMode mode;
    String note;
    boolean refactored;

    CriticalSection(SourceFile file, Node node, BlockStmt body, String enclosingType, String member) {
        this.file = file;
        this.node = node;
        this.body = body;
        this.enclosingType = enclosingType;
        this.member = member;
    }

    boolean isMethod() {
        return node instanceof MethodDeclaration;
    }

    SynchronizedStmt syncStmt() {
        return (SynchronizedStmt) node;
    }

    int line() {
        return node.getRange().map(r -> r.begin.line).orElse(0);
    }

    int length() {
        return file.end(node) - file.start(node);
    }
}
