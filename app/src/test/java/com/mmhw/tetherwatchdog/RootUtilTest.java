package com.mmhw.tetherwatchdog;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RootUtilTest {

    @Test
    public void resetScript_selfDetectsEthernetAndDoesNotForceRndisFirst() {
        String script = RootUtil.buildResetScript();
        int eth = script.indexOf("ETH=");
        int rndis = script.indexOf("svc usb setFunctions rndis,adb");
        assertTrue(eth >= 0);
        assertTrue(rndis >= 0);
        assertTrue("Must probe ethernet before forcing RNDIS", eth < rndis);
        assertTrue(script.contains("if [ -n \"$ETH\" ]"));
        // RNDIS must be in the else branch so a hub is not dropped
        assertTrue(script.contains("else\n" + "svc usb setFunctions rndis,adb")
                || script.contains("else\nsvc usb setFunctions rndis,adb"));
    }

    @Test
    public void enableScript_ethernetSkipsRndisWhenEthPresent() {
        String script = RootUtil.buildEnableTetherScript(UsbLinkMonitor.MODE_ETHERNET);
        assertTrue(script.contains("ETH="));
        assertTrue(script.contains("ndc tether interface add"));
        assertTrue(script.contains("svc usb setFunctions rndis,adb"));
        assertFalse("RNDIS must not run unconditionally",
                script.trim().startsWith("svc usb setFunctions"));
    }

    @Test
    public void ethernetTuning_isDistinctFromUsb() {
        String script = RootUtil.buildResetScript();
        // Ethernet: full frames, not the RNDIS 1440 workaround
        assertTrue(script.contains("mtu 1500"));
        assertTrue(script.contains("mtu 1440"));
        // Ethernet-only performance / correctness knobs
        assertTrue(script.contains("rp_filter"));
        assertTrue(script.contains("TCPMSS --clamp-mss-to-pmtu"));
        assertTrue(script.contains("fq_codel"));
        assertTrue(script.contains("autosuspend"));
        assertTrue(script.contains("txqueuelen 5000"));
        assertTrue(script.contains("tcp_mtu_probing=1"));
        assertTrue(script.contains("tcp_slow_start_after_idle=0"));
        // USB 1440 must stay on the gadget path, not be applied to $ETH
        int ethMtu = script.indexOf("\"$ETH\" mtu 1500");
        int usbMtu = script.indexOf("mtu 1440");
        assertTrue(ethMtu >= 0);
        assertTrue(usbMtu >= 0);
        assertTrue("USB 1440 belongs in the else/RNDIS branch",
                script.indexOf("else\n") < usbMtu || script.indexOf("else\nsvc") < usbMtu);
    }
}
