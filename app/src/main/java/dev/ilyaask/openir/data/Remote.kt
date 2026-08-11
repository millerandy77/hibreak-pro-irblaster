package dev.ilyaask.openir.data

import kotlinx.serialization.Serializable

@Serializable
data class Remote(
    val id: String,
    val name: String,
    val deviceType: Int,
    val brandIndex: Int = -1,        // -1 = fully learned/custom remote
    val keys: List<RemoteKey> = emptyList(),
)

@Serializable
data class RemoteKey(
    val code: Int,
    val label: String,
    /** Hex of the raw captured timing bytes, for learned/custom keys (replay via codec). */
    val learnedHex: String? = null,
)
