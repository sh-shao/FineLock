package finelock.core;

import java.util.Set;

/**
 * Which library (non-project) methods are read-only.
 *
 * <p>The paper treats every library method whose code is unavailable as a write (rule 3 of the
 * Read-Write Identifier). Taken literally that turns almost every critical section into a write
 * section ({@code map.get}, {@code list.size}, {@code String.equals} ...), so FineLock keeps a
 * conservative list of JDK methods known not to modify shared state. Everything else is still a write.
 */
final class LibraryModel {

    /** Immutable value types: every method is read-only. */
    private static final Set<String> PURE_TYPES = Set.of(
            "java.lang.String", "java.lang.Math", "java.lang.StrictMath", "java.lang.Integer",
            "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double", "java.lang.Float",
            "java.lang.Character", "java.lang.Boolean", "java.lang.Number", "java.lang.CharSequence",
            "java.lang.Enum", "java.lang.Record", "java.lang.Comparable",
            "java.util.Objects", "java.util.Optional", "java.util.OptionalInt", "java.util.OptionalLong",
            "java.util.OptionalDouble", "java.util.UUID", "java.math.BigInteger", "java.math.BigDecimal",
            "java.util.stream.Collectors");

    private static final Set<String> PURE_PACKAGES = Set.of("java.time.", "java.util.stream.");

    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString", "getClass");

    /** Read-only methods of collections, maps, iterators and atomics (java.util and java.util.concurrent). */
    private static final Set<String> COLLECTION_READS = Set.of(
            "get", "getOrDefault", "size", "isEmpty", "contains", "containsKey", "containsValue", "containsAll",
            "indexOf", "lastIndexOf", "peek", "peekFirst", "peekLast", "element", "getFirst", "getLast",
            "first", "last", "floor", "ceiling", "higher", "lower", "firstKey", "lastKey", "floorKey",
            "ceilingKey", "higherKey", "lowerKey", "firstEntry", "lastEntry", "floorEntry", "ceilingEntry",
            "higherEntry", "lowerEntry", "headMap", "tailMap", "subMap", "headSet", "tailSet", "subSet",
            "subList", "keySet", "values", "entrySet", "iterator", "listIterator", "spliterator", "stream",
            "parallelStream", "forEach", "toArray", "comparator", "descendingMap", "descendingSet",
            "navigableKeySet", "descendingKeySet", "descendingIterator", "hasNext", "next", "hasPrevious",
            "previous", "nextIndex", "previousIndex", "getKey", "getValue", "hasMoreElements", "nextElement",
            "elements", "keys", "mappingCount", "getPlain", "getAcquire", "getOpaque", "intValue", "longValue",
            "doubleValue", "floatValue", "sum", "isReadOnly", "length", "charAt", "codePointAt", "substring",
            "subSequence", "capacity", "reversed");

    private static final Set<String> ARRAYS_READS = Set.of("asList", "copyOf", "copyOfRange", "equals",
            "deepEquals", "hashCode", "deepHashCode", "toString", "deepToString", "binarySearch", "stream",
            "compare", "mismatch");

    private static final Set<String> COLLECTIONS_READS = Set.of("unmodifiableCollection", "unmodifiableList",
            "unmodifiableSet", "unmodifiableMap", "unmodifiableSortedSet", "unmodifiableSortedMap",
            "unmodifiableNavigableSet", "unmodifiableNavigableMap", "emptyList", "emptySet", "emptyMap",
            "emptyIterator", "singletonList", "singleton", "singletonMap", "nCopies", "max", "min",
            "frequency", "binarySearch", "disjoint", "list");

    private static final Set<String> SYSTEM_READS = Set.of("currentTimeMillis", "nanoTime", "identityHashCode",
            "getProperty", "lineSeparator", "getenv");

    private static final Set<String> CLASS_READS = Set.of("getName", "getSimpleName", "getCanonicalName",
            "getTypeName", "isInstance", "cast", "isAssignableFrom", "isInterface", "isArray", "isEnum",
            "getComponentType", "getSuperclass", "desiredAssertionStatus");

    private static final Set<String> THREAD_READS = Set.of("currentThread", "getName", "getId", "threadId",
            "isInterrupted", "isAlive", "isDaemon", "getPriority", "getState");

    /** Types whose objects may be mutated freely when the object was created in the same method. */
    private static final Set<String> LOCAL_BUILDER_TYPES = Set.of(
            "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.AbstractStringBuilder",
            "java.util.StringJoiner", "java.lang.Appendable");

    private final Set<String> extra;

    LibraryModel(Set<String> extra) {
        this.extra = extra;
    }

    boolean isReadOnly(String type, String method) {
        if (extra.contains(type + "#" + method) || extra.contains(type + "#*")) return true;
        if (PURE_TYPES.contains(type)) return true;
        for (String p : PURE_PACKAGES) if (type.startsWith(p)) return true;
        if (OBJECT_METHODS.contains(method) && type.startsWith("java.")) return true;
        if (type.equals("java.util.Arrays")) return ARRAYS_READS.contains(method);
        if (type.equals("java.util.Collections")) return COLLECTIONS_READS.contains(method);
        if (type.equals("java.lang.System")) return SYSTEM_READS.contains(method);
        if (type.equals("java.lang.Class")) return CLASS_READS.contains(method);
        if (type.equals("java.lang.Thread")) return THREAD_READS.contains(method);
        if (type.equals("java.util.List") || type.equals("java.util.Set") || type.equals("java.util.Map")) {
            if (method.equals("of") || method.equals("copyOf") || method.equals("entry") || method.equals("ofEntries")) {
                return true;
            }
        }
        if (type.startsWith("java.util.") || type.equals("java.lang.Iterable") || isLocalBuilderType(type)) {
            return COLLECTION_READS.contains(method);
        }
        return false;
    }

    /**
     * Methods on a collection or builder that the current method itself just created only change that
     * thread-local object (e.g. {@code StringBuilder sb = new StringBuilder(); sb.append(x)}).
     */
    boolean isLocalObjectType(String type) {
        if (isLocalBuilderType(type)) return true;
        return type.startsWith("java.util.") && !type.startsWith("java.util.concurrent.")
                && !type.startsWith("java.util.logging.") && !type.equals("java.util.Timer");
    }

    private static boolean isLocalBuilderType(String type) {
        return LOCAL_BUILDER_TYPES.contains(type);
    }
}
