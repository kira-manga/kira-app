package me.manga.kira.platform.backup

/** Bounds the index INCLUDING implicit parents, before handing names to Okio's index builder. */
internal class Zip32Names(
    private val limits: BackupZipLimits,
) {
    private val kinds = mutableMapOf<String, Kind>()
    private val indexedNameBytes = BackupByteBudget(limits.maxDirectoryBytes)

    fun add(name: String) {
        requireZip(name.isNotEmpty() && !name.startsWith('/'))
        requireZip(name.none { it == '\\' || it == ':' || it.code < ASCII_SPACE || it in '\u007F'..'\u009F' })
        val directory = name.endsWith('/')
        val canonical = if (directory) name.dropLast(1) else name
        requireZip(canonical.isNotEmpty())
        var start = 0
        while (true) {
            val slash = canonical.indexOf('/', start)
            val end = if (slash == -1) canonical.length else slash
            val segment = canonical.substring(start, end)
            requireZip(segment.isNotEmpty() && segment != "." && segment != "..")
            val kind = if (slash != -1) Kind.IMPLICIT_DIRECTORY else if (directory) Kind.DIRECTORY else Kind.FILE
            addIndexName(canonical.substring(0, end), kind)
            if (slash == -1) break
            start = slash + 1
        }
    }

    private fun addIndexName(
        name: String,
        kind: Kind,
    ) {
        val existing = kinds[name]
        if (existing == null) {
            if (kinds.size >= limits.maxEntries) throw BackupImportLimitExceeded()
            indexedNameBytes.consume(name.encodeToByteArray().size.toLong())
            kinds[name] = kind
        } else if (kind == Kind.IMPLICIT_DIRECTORY) {
            requireZip(existing != Kind.FILE)
        } else {
            requireZip(existing == Kind.IMPLICIT_DIRECTORY && kind == Kind.DIRECTORY)
            kinds[name] = kind
        }
    }

    private enum class Kind { IMPLICIT_DIRECTORY, DIRECTORY, FILE }
}

internal fun decodeZipName(bytes: ByteArray): String =
    try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (failure: IllegalArgumentException) {
        throw InvalidBackupArchive(failure)
    }

private const val ASCII_SPACE = 0x20
