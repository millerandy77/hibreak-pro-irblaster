package dev.ilyaask.openir.blaster

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vendor 55 AA multi-part frame splitting (FragmentAIR convention). */
class IrBlasterTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun IrBlaster.Companion.split(f: ByteArray) = IrBlaster.split55aa(f)

    @Test
    fun singleFrameWithoutPrefixPassesThrough() {
        val f = hex("3002814e4e353f4b")
        assertEquals(listOf(f.toList()), IrBlaster.split55aa(f).map { it.toList() })
    }

    @Test
    fun twoPartsSplitOnDelimiter() {
        // 55aa 3003aaaa 55aa 3003bbbb
        val f = hex("55aa3003aaaa55aa3003bbbb")
        val parts = IrBlaster.split55aa(f)
        assertEquals(2, parts.size)
        assertEquals(hex("3003aaaa").toList(), parts[0].toList())
        assertEquals(hex("3003bbbb").toList(), parts[1].toList())
    }

    @Test
    fun threePartsWithEmptyPieceSkipped() {
        // 55aa <p1> 55aa 55aa <p2> — adjacent delimiters produce an empty piece, skipped
        val f = hex("55aa30030155aa55aa300302")
        val parts = IrBlaster.split55aa(f)
        assertEquals(2, parts.size)
        assertEquals(hex("300301").toList(), parts[0].toList())
        assertEquals(hex("300302").toList(), parts[1].toList())
    }

    @Test
    fun prefixOnlyReturnsOriginal() {
        // starts with 55aa but has no payload after the delimiter
        val f = hex("55aa")
        assertEquals(1, IrBlaster.split55aa(f).size)
    }
}
