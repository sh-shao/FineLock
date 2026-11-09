package finelock.core;

import java.nio.file.Path;

/**
 * What FineLock found and did for one synchronized method or block.
 *
 * @param mode       the lock used after refactoring; null when the section was left unchanged or merged
 * @param refactored true if the section was rewritten
 * @param note       why a weaker lock was chosen, or why the section was not refactored
 */
public record SectionReport(Path file, int line, String type, String member, String monitor, boolean method,
                            String pattern, String automatonTrace, LockMode mode, boolean refactored, String note) {

    static SectionReport of(CriticalSection s) {
        return new SectionReport(s.file.path(), s.line(), s.enclosingType == null ? "<anonymous>" : s.enclosingType,
                s.member, s.monitor == null ? "?" : s.monitor.toString(), s.isMethod(), s.pattern,
                s.automaton == null ? null : s.automaton.trace(), s.refactored ? s.mode : null, s.refactored, s.note);
    }
}
