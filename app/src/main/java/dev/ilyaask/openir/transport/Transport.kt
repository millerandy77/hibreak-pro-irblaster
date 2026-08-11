package dev.ilyaask.openir.transport

/** Outcome of opening a transport. */
sealed interface OpenResult {
    data object Success : OpenResult
    data class Failure(val reason: String) : OpenResult
}

/**
 * Uniform transport interface (mirrors the original app's `et.song.tg.face.ITg`).
 * Implementation: [IoTransport] (vendor /dev node for the built-in IR blaster).
 *
 * `write`/`read` move already-framed IR bytes; framing/encoding is the job of the codec layer.
 */
interface Transport : AutoCloseable {
    val kind: TransportKind
    suspend fun open(): OpenResult
    suspend fun write(frame: ByteArray): Int
    suspend fun read(into: ByteArray, timeoutMs: Long): Int
    override fun close()
}

enum class TransportKind { IO }
