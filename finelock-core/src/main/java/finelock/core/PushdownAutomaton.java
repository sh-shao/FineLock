package finelock.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * The pushdown automaton M_fg = (Q, Σ, Γ, δ, q0, Z0, F) of Section 3.2 / Figure 3.
 *
 * <ul>
 *   <li>Q = {q0..q6}, start state q0, F = {q1..q6}</li>
 *   <li>Σ = {r, w, c, e}: read, write, start of an if (its condition is a read), end of an if</li>
 *   <li>Γ = {Z0, V, C, D, A, B}: Z0 empty stack, V halt (fall back to a write lock),
 *       C inside an if, D downgrading, A read-then-write split, B write-then-read split</li>
 * </ul>
 *
 * <p>The transitions are exactly those drawn in Figure 3, plus two that the figure leaves out
 * but that are needed for realistic code (both are marked "extension" below):
 * reads inside a write-if in q4 ({@code r, C/C}), and the end of a read-only if after a
 * write-then-read split in q5 ({@code e, B/B}).
 */
public final class PushdownAutomaton {

    public enum State { Q0, Q1, Q2, Q3, Q4, Q5, Q6 }

    /** Outcome of running a pattern through the automaton. */
    public record Result(String pattern, State state, boolean halted, boolean rejected, String trace) {
        /** True if the pattern was consumed completely and the automaton stopped in a final state. */
        public boolean accepted() {
            return !halted && !rejected;
        }

        /** The lock inferred from the final state. Halted or rejected patterns get a write lock for safety. */
        public LockMode mode() {
            if (!accepted()) {
                return LockMode.WRITE;
            }
            return switch (state) {
                case Q0, Q1 -> LockMode.READ;
                case Q2 -> LockMode.WRITE;
                case Q3 -> LockMode.SPLIT_READ_WRITE;
                case Q4 -> LockMode.SPLIT_CONDITIONAL_WRITE;
                case Q5 -> LockMode.SPLIT_WRITE_READ;
                case Q6 -> LockMode.DOWNGRADE;
            };
        }
    }

    private enum Op { KEEP, PUSH, POP }

    private record Move(State next, Op op, char push) {}

    private static final char Z0 = 'Z';
    private static final Map<String, Move> DELTA = new HashMap<>();

    private static void t(State q, char in, char top, State next, Op op, char push) {
        DELTA.put(key(q, in, top), new Move(next, op, push));
    }

    private static void keep(State q, char in, char top, State next) {
        t(q, in, top, next, Op.KEEP, ' ');
    }

    private static void push(State q, char in, char top, State next, char sym) {
        t(q, in, top, next, Op.PUSH, sym);
    }

    private static void pop(State q, char in, char top, State next) {
        t(q, in, top, next, Op.POP, ' ');
    }

    static {
        // q0
        keep(State.Q0, 'r', Z0, State.Q1);
        push(State.Q0, 'c', Z0, State.Q1, 'C');
        keep(State.Q0, 'w', Z0, State.Q2);
        // q1: only reads so far
        keep(State.Q1, 'r', Z0, State.Q1);
        keep(State.Q1, 'r', 'C', State.Q1);
        push(State.Q1, 'c', Z0, State.Q1, 'C');
        push(State.Q1, 'c', 'C', State.Q1, 'C');
        pop(State.Q1, 'e', 'C', State.Q1);
        push(State.Q1, 'w', Z0, State.Q3, 'A');
        keep(State.Q1, 'w', 'C', State.Q4);
        // q2: only writes so far
        keep(State.Q2, 'w', Z0, State.Q2);
        push(State.Q2, 'c', Z0, State.Q5, 'B');
        push(State.Q2, 'r', Z0, State.Q5, 'B');
        // q3: read followed by write
        keep(State.Q3, 'w', 'A', State.Q3);
        push(State.Q3, 'r', 'A', State.Q3, 'V');
        push(State.Q3, 'c', 'A', State.Q3, 'V');
        // q4: write inside an if
        keep(State.Q4, 'w', 'C', State.Q4);
        keep(State.Q4, 'r', 'C', State.Q4); // extension: reads inside the write-if
        push(State.Q4, 'c', 'C', State.Q4, 'C');
        pop(State.Q4, 'e', 'C', State.Q4);
        push(State.Q4, 'w', Z0, State.Q4, 'V');
        push(State.Q4, 'r', Z0, State.Q6, 'D');
        push(State.Q4, 'c', Z0, State.Q6, 'D');
        // q5: write followed by read
        keep(State.Q5, 'c', 'B', State.Q5);
        keep(State.Q5, 'r', 'B', State.Q5);
        keep(State.Q5, 'e', 'B', State.Q5); // extension: end of a read-only if
        push(State.Q5, 'w', 'B', State.Q5, 'V');
        // q6: downgrading
        keep(State.Q6, 'r', 'D', State.Q6);
        keep(State.Q6, 'c', 'D', State.Q6);
        keep(State.Q6, 'e', 'D', State.Q6);
        push(State.Q6, 'w', 'D', State.Q6, 'V');
    }

    private static String key(State q, char in, char top) {
        return q.name() + in + top;
    }

    private PushdownAutomaton() {}

    public static Result run(String pattern) {
        Deque<Character> stack = new ArrayDeque<>();
        stack.push(Z0);
        State q = State.Q0;
        StringBuilder trace = new StringBuilder(q.name().toLowerCase());
        for (int i = 0; i < pattern.length(); i++) {
            char x = pattern.charAt(i);
            char top = stack.peek();
            Move m = DELTA.get(key(q, x, top));
            if (m == null) {
                trace.append(" -").append(x).append("-> reject");
                return new Result(pattern, q, false, true, trace.toString());
            }
            switch (m.op) {
                case PUSH -> stack.push(m.push);
                case POP -> stack.pop();
                case KEEP -> { }
            }
            q = m.next;
            trace.append(" -").append(x).append("-> ").append(q.name().toLowerCase());
            if (stack.peek() == 'V') {
                trace.append(" (halt)");
                return new Result(pattern, q, true, false, trace.toString());
            }
        }
        return new Result(pattern, q, false, false, trace.toString());
    }
}
