package finelock.core;

/**
 * Identifies a monitor object up to aliasing: {@code this} of a class, the {@code Class}
 * object of a class, or the object held in a final field.
 *
 * <p>Two critical sections with equal keys (after the class-hierarchy merge done by
 * {@link LocksetAnalysis}) must share one {@code ReentrantReadWriteLock}.
 */
public record MonitorKey(Kind kind, String typeName, String fieldName, boolean isStatic) {

    public enum Kind { THIS, CLASS, FIELD }

    public static MonitorKey ofThis(String type) {
        return new MonitorKey(Kind.THIS, type, null, false);
    }

    public static MonitorKey ofClass(String type) {
        return new MonitorKey(Kind.CLASS, type, null, true);
    }

    public static MonitorKey ofField(String type, String field, boolean isStatic) {
        return new MonitorKey(Kind.FIELD, type, field, isStatic);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case THIS -> simple(typeName) + ".this";
            case CLASS -> simple(typeName) + ".class";
            case FIELD -> simple(typeName) + "." + fieldName;
        };
    }

    static String simple(String qualified) {
        int i = qualified.lastIndexOf('.');
        return i < 0 ? qualified : qualified.substring(i + 1);
    }
}
