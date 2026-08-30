package com.cruxcoach.domain.community

import kotlin.test.Test
import kotlin.test.assertEquals

class FramesHashPortableTest {
    @Test
    fun standard_sha256_vectors_cover_empty_single_and_multiple_blocks() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(byteArrayOf()),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex(
                "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
                    .encodeToByteArray(),
            ),
        )
    }

    @Test
    fun canonical_frames_hash_preserves_the_published_format() {
        assertEquals(
            "4f2498ab39e776473cf05377e0ede71d44727087ba22d4b7ea8de0618674b402",
            FramesHash.of("p1164r12p1233r13p1392r14", layoutId = 1),
        )
    }
}
