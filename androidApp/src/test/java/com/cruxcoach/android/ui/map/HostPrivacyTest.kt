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
}
