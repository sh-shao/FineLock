package finelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushdownAutomatonTest {

    @Test
    void paperExampleCwerIsDowngrading() {
        // Section 3.2, "Examples": cached() of Figure 1(a)
        PushdownAutomaton.Result r = PushdownAutomaton.run("cwer");
        assertTrue(r.accepted());
        assertEquals(PushdownAutomaton.State.Q6, r.state());
        assertEquals(LockMode.DOWNGRADE, r.mode());
        assertEquals("q0 -c-> q1 -w-> q4 -e-> q4 -r-> q6", r.trace());
    }

    @ParameterizedTest
    @CsvSource({
            "'',        READ",
            "r,         READ",
            "rrr,       READ",
            "crer,      READ",
            "ccree,     READ",
            "w,         WRITE",
            "ww,        WRITE",
            "rw,        SPLIT_READ_WRITE",
            "rcerww,    SPLIT_READ_WRITE",
            "cwe,       SPLIT_CONDITIONAL_WRITE",
            "rcwre,     SPLIT_CONDITIONAL_WRITE",
            "ccwee,     SPLIT_CONDITIONAL_WRITE",
            "wr,        SPLIT_WRITE_READ",
            "wwcrer,    SPLIT_WRITE_READ",
            "cwerr,     DOWNGRADE",
            "cwecer,    DOWNGRADE",
            // halted by V: write lock for safety
            "rwr,       WRITE",
            "wrw,       WRITE",
            "cwew,      WRITE",
            "cwerw,     WRITE",
            "cwecwe,    WRITE",
    })
    void inference(String pattern, LockMode expected) {
        assertEquals(expected, PushdownAutomaton.run(pattern).mode(), PushdownAutomaton.run(pattern).trace());
    }

    @Test
    void haltReportsV() {
        PushdownAutomaton.Result r = PushdownAutomaton.run("rwr");
        assertTrue(r.halted());
        assertEquals(LockMode.WRITE, r.mode());
    }
}
