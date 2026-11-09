package finelock.core;

/**
 * The lock that FineLock infers for one critical section.
 *
 * <p>The paper (ISSTA'20, Table 1) groups the results into four columns:
 * downgrading lock, splitting lock, read lock and write lock. The first two are the
 * "fine-grained" locks; the last two are plain {@code ReentrantReadWriteLock} locks.
 */
public enum LockMode {
    /** q1 (or empty pattern): the whole section only reads shared state. */
    READ(Category.READ_WRITE_LOCK, "read lock"),
    /** q2, or a halted / rejected pattern: the whole section takes the write lock. */
    WRITE(Category.READ_WRITE_LOCK, "write lock"),
    /** q3: reads first, then writes. The read part runs under the read lock, the write part under the write lock. */
    SPLIT_READ_WRITE(Category.FINE_GRAINED, "splitting lock (read -> write)"),
    /** q4: the writes sit inside an if whose condition only reads. Read lock, then upgrade with re-check. */
    SPLIT_CONDITIONAL_WRITE(Category.FINE_GRAINED, "splitting lock (conditional write)"),
    /** q5: writes first, then reads. Write lock, then atomically downgrade to the read lock. */
    SPLIT_WRITE_READ(Category.FINE_GRAINED, "splitting lock (write -> read)"),
    /** q6: read lock, upgrade for a conditional write, then downgrade for the reads that follow (Figure 1(c)). */
    DOWNGRADE(Category.FINE_GRAINED, "downgrading lock");

    public enum Category { FINE_GRAINED, READ_WRITE_LOCK }

    private final Category category;
    private final String description;

    LockMode(Category category, String description) {
        this.category = category;
        this.description = description;
    }

    public Category category() {
        return category;
    }

    public String description() {
        return description;
    }

    /** True for the two columns the paper calls "splitting lock". */
    public boolean isSplitting() {
        return this == SPLIT_READ_WRITE || this == SPLIT_CONDITIONAL_WRITE || this == SPLIT_WRITE_READ;
    }
}
