package io.github.huyz0.jzap.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CoverageRecorderTest {

    @Test
    void drainReturnsHitsThenResets() {
        CoverageRecorder.init(8);
        CoverageRecorder.hit(1);
        CoverageRecorder.hit(5);
        CoverageRecorder.hit(5);

        assertArrayEquals(new int[]{1, 5}, CoverageRecorder.drain());
        assertArrayEquals(new int[]{}, CoverageRecorder.drain());
    }

    @Test
    void outOfRangeProbeIsIgnoredRatherThanThrowing() {
        CoverageRecorder.init(2);
        CoverageRecorder.hit(99);
        assertArrayEquals(new int[]{}, CoverageRecorder.drain());
        assertEquals(2, CoverageRecorder.probeCount());
    }
}
