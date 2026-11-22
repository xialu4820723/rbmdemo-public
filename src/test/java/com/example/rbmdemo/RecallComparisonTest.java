package com.example.rbmdemo;

import java.util.BitSet;
import junit.framework.TestCase;

public class RecallComparisonTest extends TestCase {
    public void testAllImplementationsEmitSameOrderedIdsIncludingTail() {
        BitSet candidates = bits(0, 2, 64, 65535, 65536, 65537, 65602);
        var data = new PerfAnalysis.Workload(65603, candidates, new BitSet[] {
                bits(0, 2, 65535, 65536, 65537, 65602), bits(0, 65535, 65536, 65602)
        });
        data.verify(new int[] {0, 65535, 65536, 65602});
    }

    public void testEmptyFilterEliminatesAllCandidates() {
        var data = new PerfAnalysis.Workload(128, bits(0, 127), new BitSet[] {new BitSet()});
        data.verify(new int[0]);
    }

    public void testEmptyCandidatesEmitNothing() {
        var data = new PerfAnalysis.Workload(128, new BitSet(), new BitSet[] {bits(0, 127)});
        data.verify(new int[0]);
    }

    public void testFixedCapacityIncludesTheLastBucketEvenIfSetIsSparse() {
        int universe = PerfAnalysis.UNIVERSE;
        var data = new PerfAnalysis.Workload(universe, bits(0), new BitSet[] {bits(0)});
        assertEquals(625000, data.flatBytes());
        assertTrue(data.roaringBytes() < data.flatBytes());
    }

    public void testRejectsBitsOutsideDeclaredUniverse() {
        try {
            new PerfAnalysis.Workload(10, bits(10), new BitSet[] {bits(0)});
            fail("expected out-of-range candidate rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("bits outside universe", expected.getMessage());
        }
    }

    private static BitSet bits(int... values) {
        BitSet bits = new BitSet();
        for (int value : values) bits.set(value);
        return bits;
    }
}
