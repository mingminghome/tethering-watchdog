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
}
