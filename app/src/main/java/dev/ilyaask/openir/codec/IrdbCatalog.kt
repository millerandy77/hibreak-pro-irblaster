package dev.ilyaask.openir.codec

import android.util.Log

/**
 * Open IR database catalog — a browseable index of community/open IR code files
 * (Flipper Zero `.ir`, LIRC `.conf`, Pronto Hex, raw timings) that the user imports
 * from a folder (e.g. the [Flipper IRDB](https://github.com/Flipper-XFW/Xtreme-Apps-IRDB)
 * tree, laid out as `Category/Brand/Model.ir`).
 *
 * This is the default "universal remote" path: MIT-clean, no proprietary binary, and the
 * widest device coverage. Each parsed file becomes a [CatalogFile] keyed by the folder
 * structure (category / brand / model). A selected signal is fed to [BigmeFrameEncoder]
 * for transmission over the built-in IR transport.
 *
 * The catalog is format-agnostic: it only needs (path components, file text) pairs; the
 * caller ([OpenIrViewModel]) supplies those from a SAF tree URI or a local directory.
 */
object IrdbCatalog {
    private const val TAG = "OpenIR/Catalog"

    /** A single imported file, parsed into one or more [IrSignal]s. */
    data class CatalogFile(
        val category: String,
        val brand: String,
        val model: String,
        val source: String,        // relative path / display name
        val signals: List<IrSignal>,
    ) {
        val signalNames: List<String> get() = signals.indices.map { i ->
            val s = signals[i]
            "Signal ${i + 1} · ${s.frequencyHz} Hz · ${s.timings.size} edges"
        }
    }

    /** Built catalog: category -> (brand -> files). */
    data class Catalog(val byCategory: Map<String, Map<String, List<CatalogFile>>>) {
        val categories: List<String> get() = byCategory.keys.sorted()
        fun brands(category: String): List<String> =
            (byCategory[category]?.keys ?: emptyList()).sorted()
        fun files(category: String, brand: String): List<CatalogFile> =
            byCategory[category]?.get(brand) ?: emptyList()
        val isEmpty: Boolean get() = byCategory.isEmpty()
        val fileCount: Int get() = byCategory.values.sumOf { b -> b.values.sumOf { it.size } }
    }

    /**
     * Build a catalog from a sequence of (path parts, file text). `pathParts` is the file's
     * path split into components, root-relative (e.g. `["tv","LG","KM-2025.ir"]`). The last
     * component is the model/file name; the preceding ones are brand, then category. Files
     * whose path is too shallow or that yield no signals are skipped.
     */
    fun build(pathAndText: Sequence<Pair<List<String>, String>>): Catalog {
        val byCat = sortedMapOf<String, MutableMap<String, MutableList<CatalogFile>>>()
        var parsed = 0
        var skipped = 0
        for ((parts, text) in pathAndText) {
            if (parts.isEmpty()) { skipped++; continue }
            val model = parts.last().substringBeforeLast('.')
            val brand = if (parts.size >= 2) parts[parts.size - 2] else "Unknown"
            val category = if (parts.size >= 3) parts[parts.size - 3] else brand
            val signals = try {
                IrSignalParser.parse(text, parts.last())
            } catch (e: Exception) {
                Log.w(TAG, "parse failed for ${parts.joinToString("/")}: ${e.message}"); emptyList()
            }
            if (signals.isEmpty()) { skipped++; continue }
            val file = CatalogFile(
                category = cleanName(category),
                brand = cleanName(brand),
                model = model.ifBlank { parts.last() },
                source = parts.joinToString("/"),
                signals = signals,
            )
            byCat.getOrPut(file.category) { mutableMapOf() }
                .getOrPut(file.brand) { mutableListOf() }.add(file)
            parsed++
        }
        Log.i(TAG, "catalog: $parsed files, $skipped skipped, ${byCat.size} categories")
        return Catalog(byCat.mapValues { it.value.mapValues { it.value.toList() } })
    }

    /** Normalise a folder name into a display label (swap underscores, title-case minimally). */
    private fun cleanName(s: String): String =
        s.replace('_', ' ').trim().ifBlank { s }
}
