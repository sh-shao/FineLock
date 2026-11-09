package finelock.core;

import java.util.LinkedHashSet;
import java.util.Set;

/** Settings for a FineLock run. */
public final class FineLockOptions {

    /** Infer downgrading locks (q6). When off, such sections get a write lock. */
    public boolean enableDowngrade = true;

    /** Infer splitting locks (q3, q4, q5). When off, such sections get a write lock. */
    public boolean enableSplitting = true;

    /** Create the locks as {@code new ReentrantReadWriteLock(true)}. */
    public boolean fairLocks = false;

    /** Base name of the generated instance lock field. */
    public String instanceLockName = "rwLock";

    /** Base name of the generated static lock field. */
    public String staticLockName = "RW_LOCK";

    /**
     * Extra library methods to treat as read-only, as {@code fully.qualified.Type#method}
     * or {@code fully.qualified.Type#*}. Any library method not known to be read-only is a write
     * (rule 3 in Section 3.1 of the paper).
     */
    public final Set<String> extraReadOnlyMethods = new LinkedHashSet<>();

    public FineLockOptions copy() {
        FineLockOptions o = new FineLockOptions();
        o.enableDowngrade = enableDowngrade;
        o.enableSplitting = enableSplitting;
        o.fairLocks = fairLocks;
        o.instanceLockName = instanceLockName;
        o.staticLockName = staticLockName;
        o.extraReadOnlyMethods.addAll(extraReadOnlyMethods);
        return o;
    }
}
