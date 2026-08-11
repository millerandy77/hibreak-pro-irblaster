package dev.ilyaask.openir.codec

/**
 * Multi-format IR code parser. Converts community/open IR formats into the [IrSignal] IR
 * that [BigmeFrameEncoder] turns into a Bigme device frame.
 *
 * Supported formats (selected by content sniffing, fall back to extension):
 *  - Flipper Zero `.ir` (`Filetype: IR library file`, `type: parsed`/`type: raw`, `data:`)
 *  - LIRC `.conf` (`begin remote` / `begin raw_codes` with us timings)
 *  - Pronto Hex (leading `0000`/`0100` word, 4-hex-digit space-separated words)
 *  - Raw timings (whitespace/comma-separated integers, interpreted as us mark/space)
 *
 * These are open, documented formats — no reverse engineering, no hardware validation needed
 * for the parsing itself. (Transmitting the result still depends on the encoder's payload
 * unit/header, which is the one validated-on-hardware piece — see [BigmeFrameEncoder].)
 */
object IrSignalParser {

    /** Parse one IR code from a file's text content. Returns null if nothing usable found. */
    fun parse(text: String, filename: String? = null): List<IrSignal> {
        val t = text.trim()
        return when {
            t.startsWith("Filetype: IR", ignoreCase = true) -> parseFlipper(text)
            t.contains("begin remote", ignoreCase = true) -> parseLirc(text)
            looksLikePronto(t) -> listOfNotNull(parsePronto(t))
            else -> parseRawAll(text)
        }.ifEmpty { emptyList() }
    }

    // ---- Flipper .ir -------------------------------------------------------
    // Each entry: `name: X` / `type: parsed` / `frequency: 38000` / `duty_cycle: 0.33`
    // / `data: 123 456 789 ...` (us, mark/space alternating, starts with mark).
    private fun parseFlipper(text: String): List<IrSignal> {
        val out = ArrayList<IrSignal>()
        var freq = 38000
        var data: List<Int>? = null
        var haveEntry = false
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("frequency:", true) ->
                    freq = line.substringAfter(':').trim().toIntOrNull() ?: freq
                line.startsWith("data:", true) -> {
                    data = line.substringAfter(':').ints()
                    haveEntry = true
                }
                line.startsWith("name:", true) && haveEntry && data != null -> {
                    out.add(IrSignal(freq, data!!))
                    haveEntry = false; data = null
                }
            }
        }
        if (haveEntry && data != null) out.add(IrSignal(freq, data!!))
        return out
    }

    // ---- LIRC .conf (raw_codes only) --------------------------------------
    // `frequency 38000` then `begin raw_codes` / `name Power 12 34 56 ...` (us).
    private fun parseLirc(text: String): List<IrSignal> {
        val out = ArrayList<IrSignal>()
        var freq = 38000
        var inRaw = false
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.equals("begin raw_codes", true)) { inRaw = true; continue }
            if (line.equals("end raw_codes", true)) { inRaw = false; continue }
            if (line.startsWith("frequency", true)) {
                freq = line.substringAfter("frequency").trim().toIntOrNull() ?: freq
                continue
            }
            if (inRaw && line.startsWith("name", true)) {
                val nums = line.substringAfter("name").ints()
                if (nums.isNotEmpty()) out.add(IrSignal(freq, nums))
            }
        }
        return out
    }

    // ---- Pronto Hex --------------------------------------------------------
    // Words: [form] [freqWord] [seq1] [seq2] [pairs...]. Learned (form=0000): timings derived
    // from burst pairs. us per burst = 0.241246 / freqMHz; each pair = mark,space.
    private fun parsePronto(text: String): IrSignal? {
        val words = text.split(Regex("\\s+")).filter { it.length == 4 && it.isHex() }
        if (words.size < 4) return null
        val form = words[0].toInt(16)
        if (form != 0x0000 && form != 0x0100) return null
        val freqWord = words[1].toInt(16)
        val freqHz = if (freqWord > 0) (1_000_000.0 / (freqWord * 0.241246)).toInt() else 38000
        val seq1Len = words[2].toInt(16)
        val seq2Len = words[3].toInt(16)
        val pairs = words.drop(4)
        // Pronto: carrier = 4145140 / C Hz, and one count = 1e6/carrier us (= C * 0.241246).
        val tickUs = 1_000_000.0 / freqHz
        fun seq(off: Int, len: Int): List<Int> = (0 until len).map {
            (pairs.getOrNull(off + it)?.toInt(16)?.times(tickUs))?.toInt() ?: 0
        }
        // form 0x0000: one-shot = seq1, repeat = seq2. We emit seq1 followed by seq2 as timings.
        val timings = seq(4, seq1Len) + seq(4 + seq1Len, seq2Len)
        if (timings.isEmpty()) return null
        val repeat = seq(4 + seq1Len, seq2Len)
        return IrSignal(frequencyHz = freqHz, timings = timings, repeatTimings = repeat)
    }

    private fun looksLikePronto(t: String): Boolean =
        t.lines().firstOrNull()?.let { first ->
            val w = first.split(Regex("\\s+")).filter { it.length == 4 && it.isHex() }
            w.isNotEmpty() && w[0].toInt(16) in setOf(0x0000, 0x0100)
        } ?: false

    // ---- Raw timings -------------------------------------------------------
    // One or more comma/newline-separated integer lists; each non-empty list is a signal.
    private fun parseRawAll(text: String): List<IrSignal> {
        val out = ArrayList<IrSignal>()
        for (line in text.lines()) {
            val nums = line.ints()
            if (nums.size >= 2) out.add(IrSignal(timings = nums))
        }
        if (out.isEmpty()) text.ints().takeIf { it.size >= 2 }?.let { out.add(IrSignal(timings = it)) }
        return out
    }

    private fun String.ints(): List<Int> =
        split(Regex("[\\s,;]+")).mapNotNull { it.toIntOrNull() }

    private fun String.isHex(): Boolean = all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
