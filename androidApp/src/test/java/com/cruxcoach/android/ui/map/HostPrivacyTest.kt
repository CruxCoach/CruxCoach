package com.cruxcoach.android.ui.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for [isPrivateOrLoopbackHost] — the guard that stops the gym
 * "open website" action from launching an intent at a private/loopback host
 * embedded in a community-supplied gym URL (SSRF-style local-network probe).
 */
class HostPrivacyTest {

    @Test
    fun rejects_null_blank_and_localhost() {
        assertTrue(isPrivateOrLoopbackHost(null))
        assertTrue(isPrivateOrLoopbackHost(""))
        assertTrue(isPrivateOrLoopbackHost("  "))
        assertTrue(isPrivateOrLoopbackHost("localhost"))
        assertTrue(isPrivateOrLoopbackHost("LOCALHOST"))
    }

    @Test
    fun rejects_ipv4_loopback_private_and_linklocal() {
        assertTrue(isPrivateOrLoopbackHost("127.0.0.1"))
        assertTrue(isPrivateOrLoopbackHost("10.1.2.3"))
        assertTrue(isPrivateOrLoopbackHost("172.16.0.1"))
        assertTrue(isPrivateOrLoopbackHost("172.31.255.255"))
        assertTrue(isPrivateOrLoopbackHost("192.168.1.1"))
        assertTrue(isPrivateOrLoopbackHost("169.254.0.1"))
        assertTrue(isPrivateOrLoopbackHost("0.0.0.0"))
    }

    @Test
    fun rejects_ipv6_loopback_ula_and_linklocal_even_bracketed() {
        assertTrue(isPrivateOrLoopbackHost("::1"))
        assertTrue(isPrivateOrLoopbackHost("[::1]"))
        assertTrue(isPrivateOrLoopbackHost("fc00::1"))
        assertTrue(isPrivateOrLoopbackHost("fd12:3456::1"))
        assertTrue(isPrivateOrLoopbackHost("fe80::1"))
    }

    @Test
    fun accepts_public_hostnames_and_public_ipv4() {
        assertFalse(isPrivateOrLoopbackHost("example.com"))
        assertFalse(isPrivateOrLoopbackHost("8.8.8.8"))
        assertFalse(isPrivateOrLoopbackHost("172.15.0.1")) // just below the 172.16/12 block
        assertFalse(isPrivateOrLoopbackHost("172.32.0.1")) // just above it
        assertFalse(isPrivateOrLoopbackHost("192.167.0.1")) // not 192.168
    }

    @Test
    fun rejects_alternate_ipv4_encodings_of_loopback_and_private() {
        assertTrue(isPrivateOrLoopbackHost("2130706433")) // decimal dword 127.0.0.1
        assertTrue(isPrivateOrLoopbackHost("0x7f000001")) // hex dword 127.0.0.1
        assertTrue(isPrivateOrLoopbackHost("0177.0.0.1")) // octal first octet
        assertTrue(isPrivateOrLoopbackHost("127.1")) // legacy short form
        assertTrue(isPrivateOrLoopbackHost("0x7f.0.0.1")) // hex octet
        assertTrue(isPrivateOrLoopbackHost("0xc0a80001")) // 192.168.0.1 hex dword
    }

    @Test
    fun rejects_ipv4_mapped_ipv6_loopback() {
        assertTrue(isPrivateOrLoopbackHost("::ffff:127.0.0.1"))
        assertTrue(isPrivateOrLoopbackHost("[::ffff:127.0.0.1]"))
        assertTrue(isPrivateOrLoopbackHost("::ffff:7f00:0001")) // hex-group form
    }

    @Test
    fun still_accepts_public_hosts_after_hardening() {
        assertFalse(isPrivateOrLoopbackHost("example.com"))
        assertFalse(isPrivateOrLoopbackHost("8.8.8.8"))
        assertFalse(isPrivateOrLoopbackHost("0x08080808")) // 8.8.8.8 in hex — public
        assertFalse(isPrivateOrLoopbackHost("cafe.example.com"))
    }
}
