package com.mmhw.tetherwatchdog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UsbLinkMonitorTest {

    @Test
    public void normalizeSpeed_textLabels() {
        assertEquals("high-speed", UsbLinkMonitor.normalizeSpeed("high-speed"));
        assertEquals("super-speed", UsbLinkMonitor.normalizeSpeed("super-speed"));
        assertEquals("super-speed+", UsbLinkMonitor.normalizeSpeed("super-speed-plus"));
        assertEquals("full-speed", UsbLinkMonitor.normalizeSpeed("full-speed"));
        assertEquals("low-speed", UsbLinkMonitor.normalizeSpeed("low-speed"));
    }

    @Test
    public void normalizeSpeed_usbSpeedEnum() {
        // include/uapi/linux/usb/ch9.h USB_SPEED_*
        assertEquals("low-speed", UsbLinkMonitor.normalizeSpeed("1"));
        assertEquals("full-speed", UsbLinkMonitor.normalizeSpeed("2"));
        assertEquals("high-speed", UsbLinkMonitor.normalizeSpeed("3"));
        assertEquals("super-speed", UsbLinkMonitor.normalizeSpeed("5"));
        assertEquals("super-speed+", UsbLinkMonitor.normalizeSpeed("6"));
        assertEquals("unknown", UsbLinkMonitor.normalizeSpeed("0"));
    }

    @Test
    public void normalizeSpeed_mbpsIntegers() {
        assertEquals("full-speed", UsbLinkMonitor.normalizeSpeed("12"));
        assertEquals("high-speed", UsbLinkMonitor.normalizeSpeed("480"));
        assertEquals("super-speed", UsbLinkMonitor.normalizeSpeed("5000"));
        assertEquals("super-speed+", UsbLinkMonitor.normalizeSpeed("10000"));
        assertEquals("super-speed+", UsbLinkMonitor.normalizeSpeed("20000"));
        assertEquals("low-speed", UsbLinkMonitor.normalizeSpeed("1.5"));
    }

    @Test
    public void speedTierLabel_mapsGenerations() {
        assertEquals("USB 2.0", UsbLinkMonitor.speedTierLabel("high-speed"));
        assertEquals("USB 3.0", UsbLinkMonitor.speedTierLabel("super-speed"));
        assertEquals("USB 3.1+", UsbLinkMonitor.speedTierLabel("super-speed+"));
        assertEquals("USB 1.1", UsbLinkMonitor.speedTierLabel("full-speed"));
        assertEquals("USB · —", UsbLinkMonitor.speedTierLabel("unknown"));
    }

    @Test
    public void formatRate_scales() {
        assertEquals("0 KB/s", UsbLinkMonitor.formatRate(0));
        assertEquals("12.5 KB/s", UsbLinkMonitor.formatRate(12.5));
        assertEquals("1.5 MB/s", UsbLinkMonitor.formatRate(1500));
    }

    @Test
    public void isEthernetIfaceName_matchesHubAdapters() {
        assertEquals(true, UsbLinkMonitor.isEthernetIfaceName("eth0"));
        assertEquals(true, UsbLinkMonitor.isEthernetIfaceName("eth1"));
        assertEquals(true, UsbLinkMonitor.isEthernetIfaceName("usbeth0"));
        assertEquals(true, UsbLinkMonitor.isEthernetIfaceName("lan0"));
        assertEquals(false, UsbLinkMonitor.isEthernetIfaceName("rndis0"));
        assertEquals(false, UsbLinkMonitor.isEthernetIfaceName("wlan0"));
        assertEquals(false, UsbLinkMonitor.isEthernetIfaceName("usb0"));
        assertEquals(false, UsbLinkMonitor.isEthernetIfaceName("rmnet_data0"));
    }

    @Test
    public void detectTetherMode_hubWinsOverUsbGadget() {
        assertEquals(UsbLinkMonitor.MODE_ETHERNET,
                UsbLinkMonitor.detectTetherMode("eth0", true, true, "rndis0"));
        assertEquals(UsbLinkMonitor.MODE_USB,
                UsbLinkMonitor.detectTetherMode(null, true, false, null));
        assertEquals(UsbLinkMonitor.MODE_USB,
                UsbLinkMonitor.detectTetherMode(null, false, true, "rndis0"));
        assertEquals(UsbLinkMonitor.MODE_NONE,
                UsbLinkMonitor.detectTetherMode(null, false, false, null));
    }

    @Test
    public void speedTierLabel_ethernet() {
        assertEquals("Ethernet", UsbLinkMonitor.speedTierLabel("ethernet"));
    }

    @Test
    public void ethernetTetherAlive_needsLinkAndIpv4() {
        assertEquals(false, UsbLinkMonitor.ethernetTetherAlive(false, true, true));
        assertEquals(false, UsbLinkMonitor.ethernetTetherAlive(true, false, true));
        assertEquals(false, UsbLinkMonitor.ethernetTetherAlive(true, true, false));
        assertEquals(true, UsbLinkMonitor.ethernetTetherAlive(true, true, true));
        assertEquals(false, UsbLinkMonitor.ethernetTetherAlive(true, true, true, false));
    }

    @Test
    public void ethernetRecoverAction_waitsForLinkThenEnables() {
        // Serving
        assertEquals(UsbLinkMonitor.ETH_RECOVER_IDLE,
                UsbLinkMonitor.ethernetRecoverAction(true, true, true, true));
        // Carrier down — wait, do not reset radio
        assertEquals(UsbLinkMonitor.ETH_RECOVER_WAIT,
                UsbLinkMonitor.ethernetRecoverAction(true, true, false, false));
        // iface vanished while ethernet tethering is still desired
        assertEquals(UsbLinkMonitor.ETH_RECOVER_WAIT,
                UsbLinkMonitor.ethernetRecoverAction(true, false, false, false));
        // Link is up, Android left tethering off
        assertEquals(UsbLinkMonitor.ETH_RECOVER_ENABLE,
                UsbLinkMonitor.ethernetRecoverAction(true, true, true, false));
        // Never used ethernet, nothing plugged
        assertEquals(UsbLinkMonitor.ETH_RECOVER_IDLE,
                UsbLinkMonitor.ethernetRecoverAction(false, false, false, false));
    }

    @Test
    public void isTetherLanIpv4_androidRanges() {
        assertEquals(true, UsbLinkMonitor.isTetherLanIpv4(new byte[]{(byte) 192, (byte) 168, 42, (byte) 129}));
        assertEquals(true, UsbLinkMonitor.isTetherLanIpv4(new byte[]{(byte) 192, (byte) 168, 43, 1}));
        assertEquals(false, UsbLinkMonitor.isTetherLanIpv4(new byte[]{(byte) 192, (byte) 168, 1, 1}));
        assertEquals(false, UsbLinkMonitor.isTetherLanIpv4(new byte[]{10, 0, 0, 1}));
    }
}
