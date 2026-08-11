package dev.ilyaask.openir.codec

/**
 * Pristine on-device captures (from the OpenIR/Capture logcat line, which logs
 * before any codec runs). Lets the debug screen replay a real capture without
 * needing the physical remote — used for repeatable A/B transmit tests.
 */
object DebugCaptures {
    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** NEC-style TV key, captured 2026-07-31 (110 B). */
    val real110: ByteArray = hex(
        "00a05b0035444819201935198092193519201a5a196c1880921a20198092194619953c4b461922" +
            "18341a809318341920195b196c1980921921198092194718955b4b47182019351880921a341921" +
            "195a196d188093192019809318461abcb64a47192118341880921a3418211900"
    )
}
