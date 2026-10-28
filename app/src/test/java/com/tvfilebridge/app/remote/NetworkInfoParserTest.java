package com.tvfilebridge.app.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Exercises NetworkInfoParser - a pure language port with no intended behavior change. */
public class NetworkInfoParserTest {

    @Test
    public void parseMacAddress_wlan0() {
        String ipAddr = "1: lo: <LOOPBACK,UP,LOWER_UP>\n"
                + "    link/loopback 00:00:00:00:00:00\n"
                + "13: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP>\n"
                + "    link/ether aa:bb:cc:dd:ee:ff brd ff:ff:ff:ff:ff:ff\n"
                + "    inet 192.168.1.50/24\n";

        String mac = NetworkInfoParser.parseMacAddress(ipAddr, "wlan0");
        assertEquals("aa:bb:cc:dd:ee:ff", mac);
    }

    @Test
    public void parseMacAddress_interfaceNotFound_returnsNull() {
        String ipAddr = "1: lo: <LOOPBACK>\n    link/loopback 00:00:00:00:00:00\n";
        assertNull(NetworkInfoParser.parseMacAddress(ipAddr, "wlan0"));
    }

    @Test
    public void parseMacAddress_uppercaseInputLowercasedOutput() {
        String ipAddr = "13: wlan0: <UP>\n    link/ether AA:BB:CC:DD:EE:FF\n";
        assertEquals("aa:bb:cc:dd:ee:ff", NetworkInfoParser.parseMacAddress(ipAddr, "wlan0"));
    }
}
