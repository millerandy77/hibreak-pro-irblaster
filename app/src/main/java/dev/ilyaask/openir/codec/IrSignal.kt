package dev.ilyaask.openir.codec

/**
 * Device- and format-independent IR signal — the intermediate representation every parser
 * produces and the Bigme frame encoder consumes.
 *
 * - [frequencyHz]: carrier frequency (default 38 kHz for most consumer IR).
 * - [timings]: alternating mark/space durations in **microseconds**, starting with a mark.
 *   Even-length = signal ends on a space; the encoder pads a final mark if needed.
 * - [repeatTimings]: the portion repeated on key-hold. Empty for non-repeating signals.
 *   (Many learned codes bake the repeat into [timings]; parsers that can't separate it
 *   put the whole sequence in [timings] and leave this empty.)
 */
data class IrSignal(
    val frequencyHz: Int = 38000,
    val timings: List<Int>,
    val repeatTimings: List<Int> = emptyList(),
) {
    init {
        require(timings.isNotEmpty()) { "IrSignal must have at least one timing" }
        require(timings.all { it in 0..1_000_000 }) { "timings out of range: $timings" }
        require(frequencyHz in 10_000..100_000) { "frequency out of range: $frequencyHz" }
    }
}
