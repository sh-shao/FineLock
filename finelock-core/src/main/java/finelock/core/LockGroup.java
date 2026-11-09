package finelock.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * All critical sections that lock the same monitor, and therefore must use the same
 * {@code ReentrantReadWriteLock}. Either every section of a group is refactored or none is:
 * converting only some of them would break mutual exclusion.
 */
final class LockGroup {

    final MonitorKey key;
    final List<CriticalSection> sections = new ArrayList<>();
    /** Classes whose {@code this} belongs to the group (more than one when sub- and superclass both synchronize). */
    final Set<String> types = new LinkedHashSet<>();
    final List<String> excludedBecause = new ArrayList<>();

    /** Class that declares the generated lock field. */
    String owner;
    String lockField;

    LockGroup(MonitorKey key) {
        this.key = key;
    }

    boolean excluded() {
        return !excludedBecause.isEmpty();
    }

    void exclude(String reason) {
        if (!excludedBecause.contains(reason)) excludedBecause.add(reason);
    }
}
