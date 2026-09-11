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
    public void resetScript_appliesWanAqmAndEthernetMss() {
        String script = RootUtil.buildResetScript();
        int ethQdisc = script.indexOf("tc qdisc replace dev \"$ETH\" root fq_codel");
        int ethDel = script.indexOf("tc qdisc del dev \"$ETH\" root");
        int wanQdisc = script.lastIndexOf("tc qdisc replace dev \"$MOBILE\" root fq_codel");
        assertTrue("USB-ethernet must not get fq_codel (download TX, often no BQL)",
                ethQdisc < 0);
        assertTrue(ethDel >= 0);
        assertTrue(wanQdisc >= 0);
        assertTrue("Cellular AQM should run after iface setup", wanQdisc > ethDel);
        int oldClamp = script.indexOf("TCPMSS --clamp-mss-to-pmtu");
        int setMss = script.indexOf("TCPMSS --set-mss 1400");
        assertTrue(setMss >= 0);
        // Old clamp may remain only as a delete of a leftover rule
        if (oldClamp >= 0) {
            assertTrue(script.contains("-D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"));
            assertFalse(script.contains("-A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"));
        }
    }

    @Test
    public void enableScript_ethernetSkipsRndisWhenEthPresent() {
        String script = RootUtil.buildEnableTetherScript(UsbLinkMonitor.MODE_ETHERNET);
        assertTrue(script.contains("ETH="));
        assertTrue(script.contains("cmd tethering start ethernet"));
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
        assertTrue(script.contains("TCPMSS --set-mss 1400"));
        assertTrue(script.contains("fq_codel"));
        assertTrue(script.contains("autosuspend"));
        assertTrue(script.contains("txqueuelen 1000"));
        assertFalse(script.contains("txqueuelen 5000"));
        assertTrue(script.contains("tso on"));
        assertTrue(script.contains("gro off"));
        assertFalse(script.contains("tso off"));
        assertTrue(script.contains("eee off"));
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

    @Test
    public void resetScript_doesNotHijackEthernetControlPlane() {
        String script = RootUtil.buildResetScript();
        assertFalse(script.contains("ndc tether interface add"));
        assertFalse(script.contains("ndc tether start"));
        assertFalse(script.contains("ndc nat enable"));
        assertFalse(script.contains("ip addr add 192.168.42.129"));
        assertFalse(script.contains("iptables -P FORWARD ACCEPT"));
        assertTrue(script.contains("ndc tether interface remove"));
        assertTrue(script.contains("ip addr del 192.168.42.129"));
        assertTrue(script.contains("cmd tethering start ethernet"));
        assertTrue(script.contains("cmd tethering stop ethernet"));
        int stop = script.indexOf("cmd tethering stop ethernet");
        int dataOff = script.indexOf("svc data disable");
        assertTrue("Must release ethernet tethering before the data bounce",
                stop >= 0 && dataOff >= 0 && stop < dataOff);
    }

    @Test
    public void resetScript_doesNotForcePhySpeed() {
        String script = RootUtil.buildResetScript();
        assertFalse(script.contains("speed 100 duplex full"));
        assertFalse(script.contains("speed 1000"));
        assertTrue(script.contains("ethtool -r"));
        assertTrue(script.contains("ETHSPEED\" = 10"));
        assertFalse("Unknown/-1 speed must not be treated as 10 Mbps",
                script.contains("10|-1"));
    }

    @Test
    public void resetScript_doesNotPinMobileToFirstRmnet() {
        String script = RootUtil.buildResetScript();
        assertFalse(script.contains("MOBILE=rmnet_data0"));
        assertFalse(script.contains("ip route replace default dev"));
        assertTrue(script.contains("sort -nr"));
        assertTrue(script.contains("ip route add default dev"));
    }
}
