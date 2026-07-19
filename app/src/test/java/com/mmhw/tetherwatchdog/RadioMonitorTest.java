package com.mmhw.tetherwatchdog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RadioMonitorTest {

    @Test
    public void rank_ordersAggregation() {
        assertTrue(RadioMonitor.rank(RadioMonitor.LABEL_5G_PLUS)
                > RadioMonitor.rank(RadioMonitor.LABEL_5G));
        assertTrue(RadioMonitor.rank(RadioMonitor.LABEL_5G)
                > RadioMonitor.rank(RadioMonitor.LABEL_4G_PLUS));
        assertTrue(RadioMonitor.rank(RadioMonitor.LABEL_4G_PLUS)
                > RadioMonitor.rank(RadioMonitor.LABEL_4G));
        assertTrue(RadioMonitor.rank(RadioMonitor.LABEL_4G)
                > RadioMonitor.rank(RadioMonitor.LABEL_3G));
    }

    @Test
    public void isStepDown_detectsPlusToPlain() {
        assertTrue(RadioMonitor.isStepDown(
                RadioMonitor.LABEL_4G_PLUS, RadioMonitor.LABEL_4G));
        assertTrue(RadioMonitor.isStepDown(
                RadioMonitor.LABEL_5G, RadioMonitor.LABEL_4G));
        assertFalse(RadioMonitor.isStepDown(
                RadioMonitor.LABEL_4G, RadioMonitor.LABEL_4G_PLUS));
        assertFalse(RadioMonitor.isStepDown(
                RadioMonitor.LABEL_4G, RadioMonitor.LABEL_4G));
    }

    @Test
    public void rsrpGrade_buckets() {
        assertEquals("excellent", RadioMonitor.rsrpGrade(-75));
        assertEquals("good", RadioMonitor.rsrpGrade(-85));
        assertEquals("fair", RadioMonitor.rsrpGrade(-95));
        assertEquals("weak", RadioMonitor.rsrpGrade(-105));
        assertEquals("poor", RadioMonitor.rsrpGrade(-115));
    }

    @Test
    public void mergeLabel_promotesLteWhenCaEvenIfOverrideNone() {
        // Real device case: status bar 4G+, TelephonyDisplayInfo override=NONE, ServiceState CA=true
        assertEquals(RadioMonitor.LABEL_4G_PLUS,
                RadioMonitor.mergeLabel(RadioMonitor.LABEL_4G, null, true));
        assertEquals(RadioMonitor.LABEL_4G,
                RadioMonitor.mergeLabel(RadioMonitor.LABEL_4G, null, false));
        assertEquals(RadioMonitor.LABEL_4G_PLUS,
                RadioMonitor.mergeLabel(RadioMonitor.LABEL_4G, RadioMonitor.LABEL_4G_PLUS, false));
        // Override wins for 5G NSA over plain LTE+CA
        assertEquals(RadioMonitor.LABEL_5G,
                RadioMonitor.mergeLabel(RadioMonitor.LABEL_4G, RadioMonitor.LABEL_5G, true));
    }

    @Test
    public void classifyDropContext_priorityResetThenTetherThenIdle() {
        long now = 100_000L;
        // Reset wins even if tethered
        assertEquals(RadioMonitor.CTX_AFTER_RESET,
                RadioMonitor.classifyDropContext(true, true, now - 10_000L, now));
        // Tethered without recent reset
        assertEquals(RadioMonitor.CTX_TETHERED,
                RadioMonitor.classifyDropContext(true, true, 0L, now));
        // Cable only
        assertEquals(RadioMonitor.CTX_CABLE,
                RadioMonitor.classifyDropContext(true, false, 0L, now));
        // Unplugged baseline
        assertEquals(RadioMonitor.CTX_IDLE,
                RadioMonitor.classifyDropContext(false, false, 0L, now));
        // Reset window expired
        assertEquals(RadioMonitor.CTX_TETHERED,
                RadioMonitor.classifyDropContext(true, true,
                        now - RadioMonitor.RESET_GRACE_MS - 1, now));
    }

    @Test
    public void formatDropEvent_includesContextAndHint() {
        String line = RadioMonitor.formatDropEvent(
                "4G+", "4G", "14:22:01", RadioMonitor.CTX_IDLE, true);
        assertTrue(line.contains("4G+ → 4G"));
        assertTrue(line.contains("while: " + RadioMonitor.CTX_IDLE));
        assertTrue(line.contains("No USB"));
    }
}
