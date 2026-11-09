package finelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefactoringTest {

    @TempDir
    Path tmp;

    /** Figure 1(a) of the paper. */
    private static final String CACHE = """
            package demo;

            import java.util.HashMap;
            import java.util.Map;

            public class Cache {
                private final Map<String, String> data = new HashMap<>();
                private boolean cacheValid;
                private int loads;

                // the motivating example
                public synchronized String cached(String key) {
                    if (!cacheValid) {
                        data.put(key, "value-" + key); // write into cache
                        loads++;
                        cacheValid = true;
                    }
                    return data.get(key); // read data
                }

                public synchronized void invalidate() {
                    cacheValid = false;
                }

                public synchronized int loads() {
                    return loads;
                }
            }
            """;

    @Test
    void motivatingExampleBecomesDowngradingLock() throws Exception {
        Fixture fx = new Fixture(tmp).file("demo/Cache.java", CACHE).run();
        SectionReport cached = fx.section("cached");
        assertEquals("cwwwer", cached.pattern()); // the paper writes the three writes as one "w"
        assertEquals(LockMode.DOWNGRADE, cached.mode());
        fx.expect("invalidate", LockMode.WRITE).expect("loads()", LockMode.READ);

        String out = fx.text("demo/Cache.java");
        assertTrue(out.contains("import java.util.concurrent.locks.ReentrantReadWriteLock;"), out);
        assertTrue(out.contains("private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();"), out);
        assertFalse(out.contains("synchronized"), out);
        assertTrue(out.contains("    // the motivating example\n    public String cached(String key) {"), out);
        assertTrue(out.contains("data.put(key, \"value-\" + key); // write into cache"), "comments are kept:\n" + out);
        // the condition is re-checked under the write lock (line 47 of Figure 1(c))
        assertEquals(2, out.split("if \\(!cacheValid\\)", -1).length - 1, out);

        ClassLoader cl = fx.load();
        Class<?> c = cl.loadClass("demo.Cache");
        Object cache = c.getConstructor().newInstance();
        Method cachedM = c.getMethod("cached", String.class);
        Method invalidate = c.getMethod("invalidate");
        Method loads = c.getMethod("loads");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int id = t;
            fs.add(pool.submit(() -> {
                for (int i = 0; i < 20_000; i++) {
                    Object v = cachedM.invoke(cache, "k");
                    if (!"value-k".equals(v)) throw new AssertionError(v);
                    if (id == 0 && i % 1000 == 0) invalidate.invoke(cache);
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        int n = (int) loads.invoke(cache);
        assertTrue(n >= 1 && n <= 21, "the re-check prevents duplicate loads, got " + n);
    }

    @Test
    void readAndWriteLocks() throws Exception {
        new Fixture(tmp).file("demo/Counter.java", """
                package demo;
                public class Counter {
                    private int count;
                    private final java.util.List<String> names = new java.util.ArrayList<>();
                    public synchronized int get() { return count; }
                    public synchronized void inc() { count++; }
                    public synchronized boolean has(String n) { return names.contains(n) && !n.isEmpty(); }
                    public synchronized void add(String n) { names.add(n); }
                    public synchronized void print() { System.out.println(count); }
                    public synchronized String describe() {
                        StringBuilder sb = new StringBuilder();
                        sb.append("count=").append(count);
                        for (String n : names) sb.append(',').append(n.toUpperCase());
                        return sb.toString();
                    }
                }
                """).run()
                .expect("get()", LockMode.READ)
                .expect("inc()", LockMode.WRITE)
                .expect("has(", LockMode.READ)
                .expect("add(", LockMode.WRITE)
                .expect("print()", LockMode.WRITE) // unknown library method = write (rule 3)
                .expect("describe()", LockMode.READ) // StringBuilder created locally
                .applyAndCompile();
    }

    @Test
    void interproceduralSideEffects() throws Exception {
        new Fixture(tmp).file("demo/Account.java", """
                package demo;
                public class Account {
                    private long balance;
                    private final Audit audit = new Audit();
                    public synchronized long balance() { return current(); }
                    public synchronized void deposit(long v) { apply(v); }
                    public synchronized void log() { audit.record(balance); }
                    public synchronized int even(int n) { return n == 0 ? 0 : odd(n - 1); }
                    private int odd(int n) { return n == 0 ? 1 : even(n - 1); }
                    private long current() { return balance; }
                    private void apply(long v) { balance += v; }
                }
                """).file("demo/Audit.java", """
                package demo;
                class Audit {
                    private int records;
                    void record(long v) { records++; }
                }
                """).run()
                .expect("balance()", LockMode.READ)
                .expect("deposit(", LockMode.WRITE)
                .expect("log()", LockMode.WRITE)
                .expect("even(", LockMode.READ) // mutual recursion without writes
                .applyAndCompile();
    }

    @Test
    void splittingWriteThenRead() throws Exception {
        Fixture fx = new Fixture(tmp).file("demo/Seq.java", """
                package demo;
                public class Seq {
                    private int next;
                    private final int[] history = new int[16];
                    public synchronized int advance() {
                        next++;
                        int v = next * 2;
                        return v + history[next % 16];
                    }
                    public int block() {
                        synchronized (this) {
                            next += 2;
                            int v = next;
                            return v;
                        }
                    }
                }
                """).run().expect("advance()", LockMode.SPLIT_WRITE_READ).expect("block()", LockMode.SPLIT_WRITE_READ);
        String out = fx.text("demo/Seq.java");
        assertTrue(out.contains("rwLock.readLock().lock();\n            rwLock.writeLock().unlock();"), out);
        Class<?> c = fx.load().loadClass("demo.Seq");
        Object o = c.getConstructor().newInstance();
        assertEquals(2, c.getMethod("advance").invoke(o));
        assertEquals(3, c.getMethod("block").invoke(o));
        assertEquals(8, c.getMethod("advance").invoke(o));
    }

    @Test
    void splittingReadThenWriteNeedsIndependence() throws Exception {
        new Fixture(tmp).file("demo/Stats.java", """
                package demo;
                public class Stats {
                    private int hits, total;
                    private boolean enabled = true;
                    public synchronized void reset() {
                        if (enabled) { }
                        hits = 0;
                        total = 0;
                    }
                    public synchronized void bump() {
                        int h = hits;
                        hits = h + 1;
                    }
                    public synchronized void guard() {
                        if (!enabled) return;
                        hits++;
                    }
                }
                """).run()
                .expect("reset()", LockMode.SPLIT_READ_WRITE)
                .expect("bump()", LockMode.WRITE)   // h is read under the read lock and used after it
                .expect("guard()", LockMode.WRITE)  // early return depends on the read view
                .applyAndCompile();
    }

    @Test
    void conditionalWriteWithElseChain() throws Exception {
        Fixture fx = new Fixture(tmp).file("demo/Registry.java", """
                package demo;
                import java.util.*;
                public class Registry {
                    private final Map<String, Integer> ids = new HashMap<>();
                    private int nextId;
                    public synchronized void register(String name) {
                        if (name.isEmpty()) {
                            // nothing to do
                        } else if (!ids.containsKey(name)) {
                            ids.put(name, nextId++);
                        }
                    }
                    public synchronized int idOf(String name) {
                        if (!ids.containsKey(name)) {
                            ids.put(name, nextId++);
                        }
                        return ids.get(name);
                    }
                    public synchronized void sideEffectCondition(Iterator<String> it) {
                        if (it.next().isEmpty()) {
                            nextId = 0;
                        }
                    }
                }
                """).run()
                .expect("register(", LockMode.SPLIT_CONDITIONAL_WRITE)
                .expect("idOf(", LockMode.DOWNGRADE)
                .expect("sideEffectCondition(", LockMode.WRITE);
        Class<?> c = fx.load().loadClass("demo.Registry");
        Object o = c.getConstructor().newInstance();
        Method reg = c.getMethod("register", String.class), id = c.getMethod("idOf", String.class);
        reg.invoke(o, "a");
        reg.invoke(o, "a");
        assertEquals(0, id.invoke(o, "a"));
        assertEquals(1, id.invoke(o, "b"));
        assertEquals(1, id.invoke(o, "b"));
    }

    @Test
    void waitNotifyGroupsAreLeftAlone() throws Exception {
        Fixture fx = new Fixture(tmp).file("demo/Queue1.java", """
                package demo;
                public class Queue1 {
                    private Object item;
                    public synchronized void put(Object o) { item = o; notifyAll(); }
                    public synchronized Object peek() { return item; }
                }
                """).run();
        assertFalse(fx.section("put(").refactored());
        assertFalse(fx.section("peek()").refactored());
        assertTrue(fx.section("peek()").note().contains("notifyAll"), fx.section("peek()").note());
        assertTrue(fx.result.changedFiles().isEmpty());
    }

    @Test
    void fieldAndClassMonitors() throws Exception {
        Fixture fx = new Fixture(tmp).file("demo/Pool.java", """
                package demo;
                public class Pool {
                    private final Object lock = new Object();
                    private static int created;
                    private int size;
                    public int size() { synchronized (lock) { return size; } }
                    public void grow() { synchronized (this.lock) { size++; } }
                    public static synchronized int created() { return created; }
                    public static void create() { synchronized (Pool.class) { created++; } }
                    private Object notFinal = new Object();
                    public void other() { synchronized (notFinal) { size--; } }
                }
                """).run()
                .expect("size()", LockMode.READ).expect("grow()", LockMode.WRITE)
                .expect("created()", LockMode.READ).expect("create()", LockMode.WRITE);
        assertFalse(fx.section("other()").refactored());
        String out = fx.text("demo/Pool.java");
        assertTrue(out.contains("private final ReentrantReadWriteLock lockRwLock = new ReentrantReadWriteLock();"), out);
        assertTrue(out.contains("private static final ReentrantReadWriteLock RW_LOCK = new ReentrantReadWriteLock();"), out);
        fx.applyAndCompile();
    }

    @Test
    void subclassSharesTheSuperclassLock() throws Exception {
        Fixture fx = new Fixture(tmp).file("demo/Base.java", """
                package demo;
                public class Base {
                    protected int v;
                    public synchronized int v() { return v; }
                }
                """).file("demo/Derived.java", """
                package demo;
                public class Derived extends Base {
                    public synchronized void set(int x) { v = x; }
                    class Inner {
                        void touch() { synchronized (Derived.this) { v++; } }
                    }
                }
                """).run().expect("v()", LockMode.READ).expect("set(", LockMode.WRITE).expect("touch()", LockMode.WRITE);
        assertTrue(fx.text("demo/Base.java").contains("protected final ReentrantReadWriteLock rwLock"));
        assertFalse(fx.text("demo/Derived.java").contains("new ReentrantReadWriteLock"));
        assertTrue(fx.text("demo/Derived.java").contains("Derived.this.rwLock.writeLock().lock();"));
        fx.applyAndCompile();
    }

    @Test
    void callingAWriteSectionFromAReadSectionTakesTheWriteLock() throws Exception {
        // ReentrantReadWriteLock cannot upgrade: a read-locked caller that enters a write-locked
        // callee on the same lock would deadlock. The call is a write, so the caller gets the write lock.
        new Fixture(tmp).file("demo/Nested.java", """
                package demo;
                public class Nested {
                    private int x;
                    public synchronized void inc() { x++; }
                    public synchronized int incAndGet() { inc(); return x; }
                    public synchronized int twice() { return get() + get(); }
                    public synchronized int get() { return x; }
                }
                """).run()
                .expect("incAndGet()", LockMode.SPLIT_WRITE_READ)
                .expect("twice()", LockMode.READ)
                .applyAndCompile();
    }

    @Test
    void nestedBlockOnSameMonitorIsMerged() throws Exception {
        Fixture fx = new Fixture(tmp).file("demo/M.java", """
                package demo;
                public class M {
                    private int x;
                    public synchronized int f() {
                        synchronized (this) {
                            return x;
                        }
                    }
                }
                """).run();
        assertEquals(2, fx.result.sections().size());
        assertTrue(fx.result.sections().stream().anyMatch(s -> s.refactored() && s.mode() == null));
        String out = fx.text("demo/M.java");
        assertFalse(out.contains("synchronized"), out);
        fx.applyAndCompile();
    }

    @Test
    void unsupportedMonitorsAreReported() throws Exception {
        Fixture fx = new Fixture(tmp).file("demo/U.java", """
                package demo;
                public class U {
                    private int x;
                    public synchronized int get() { return x; }
                    public void viaParam(Object o) { synchronized (o) { x++; } }
                    public static void external(U u) { synchronized (u) { u.x++; } }
                    public void anon() {
                        new Thread(new Runnable() { public synchronized void run() { } }).start();
                    }
                }
                """).run();
        assertFalse(fx.section("viaParam").refactored());
        assertTrue(fx.section("viaParam").note().contains("local variable or parameter"));
        // synchronized(u) locks the same monitor as get(): the group must stay as it is
        assertFalse(fx.section("get()").refactored(), fx.section("get()").note());
        assertFalse(fx.section("run()").refactored());
    }

    @Test
    void untouchedCodeKeepsItsFormatting() throws Exception {
        String src = """
                package demo;

                /** Doc. */
                public class F {
                \tprivate int   x ;   // odd spacing stays

                \tpublic   int y() {
                \t\treturn 1;
                \t}

                \tpublic synchronized void set(int v) {
                \t\tx = v;
                \t}
                }
                """;
        Fixture fx = new Fixture(tmp).file("demo/F.java", src).run();
        String out = fx.text("demo/F.java");
        assertTrue(out.contains("\tprivate int   x ;   // odd spacing stays\n\n\tpublic   int y() {\n\t\treturn 1;\n\t}"), out);
        assertTrue(out.contains("\tpublic void set(int v) {\n\t\trwLock.writeLock().lock();\n\t\ttry {\n\t\t\tx = v;\n\t\t} finally {\n\t\t\trwLock.writeLock().unlock();\n\t\t}\n\t}"), out);
        fx.applyAndCompile();
    }

    @Test
    void enumsRecordsAndOptions() throws Exception {
        FineLockOptions o = new FineLockOptions();
        o.enableDowngrade = false;
        o.fairLocks = true;
        Fixture fx = new Fixture(tmp).file("demo/E.java", """
                package demo;
                public enum E {
                    A, B;
                    private boolean ready;
                    private int n;
                    public synchronized int n() {
                        if (!ready) { n = 1; ready = true; }
                        return n;
                    }
                }
                """).file("demo/R.java", """
                package demo;
                public record R(int a) {
                    public synchronized int twice() { return a * 2; }
                }
                """).run(o);
        fx.expect("n()", LockMode.WRITE);
        assertTrue(fx.section("n()").note().contains("disabled"));
        assertFalse(fx.section("twice()").refactored());
        assertTrue(fx.text("demo/E.java").contains("new ReentrantReadWriteLock(true);"));
        fx.applyAndCompile();
    }

    @Test
    void lambdaBodiesAreAnalysedWhenTheLibraryRunsThem() throws Exception {
        new Fixture(tmp).file("demo/L.java", """
                package demo;
                import java.util.*;
                import java.util.stream.*;
                public class L {
                    private final List<Integer> xs = new ArrayList<>();
                    private int sum;
                    public synchronized List<Integer> evens() {
                        return xs.stream().filter(x -> x % 2 == 0).collect(Collectors.toList());
                    }
                    public synchronized void total() {
                        xs.forEach(x -> sum += x);
                    }
                    public synchronized Runnable later() {
                        return () -> sum++;
                    }
                }
                """).run()
                .expect("evens()", LockMode.READ)
                .expect("total()", LockMode.WRITE)
                .expect("later()", LockMode.READ) // the lambda is not run inside the section
                .applyAndCompile();
    }

    @Test
    void scopeLimitsWhatIsReported() throws Exception {
        Fixture fx = new Fixture(tmp).file("demo/A.java", """
                package demo;
                public class A { int x; public synchronized int x() { return x; } }
                """).file("demo/B.java", """
                package demo;
                public class B { int y; public synchronized int y() { return y; } }
                """);
        RefactoringResult r = FineLock.refactor(List.of(fx.root), List.of(),
                p -> p.getFileName().toString().equals("A.java"), new FineLockOptions());
        assertEquals(1, r.sections().size());
        assertEquals(1, r.changedFiles().size());
        assertNull(r.changedFiles().get(fx.root.resolve("demo/B.java").toAbsolutePath().normalize()));
    }
}
