package com.tvfilebridge.app.files

import android.util.Log
import com.tvfilebridge.app.connection.AdbConnectionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private const val TAG = "FileRepository"

class FileRepository(private val connectionManager: AdbConnectionManager) {

    /**
     * Lists a directory via `ls -la` shell parsing (dadb's public sync API only
     * exposes send/recv, no LIST/STAT - see spec §8 fallback). Toybox `ls -la`
     * format: perms links owner group size date time name, one entry per line,
     * preceded by a "total N" line. Handles filenames with spaces by joining
     * every token past the fixed date/time columns rather than splitting naively.
     */
    suspend fun list(path: String): Result<List<RemoteFile>> {
        val result = connectionManager.withDadb { dadb ->
            // Trailing slash forces `ls` to follow a symlinked directory (e.g.
            // /sdcard -> /storage/self/primary) rather than listing the link
            // itself as a single file entry.
            val listPath = if (path.endsWith("/")) path else "$path/"
            val response = dadb.shell("ls -la ${shellQuote(listPath)}")
            if (response.exitCode != 0) {
                throw IllegalStateException(response.errorOutput.ifBlank { response.output })
            }
            response.output.lineSequence()
                .mapNotNull { parseLsLine(it, path) }
                .sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                .toList()
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "list($path) failed: ${it.message}", it) }
        return result
    }

    suspend fun delete(path: String): Result<Unit> {
        val result = connectionManager.withDadb { dadb ->
            val response = dadb.shell("rm -rf ${shellQuote(path)}")
            if (response.exitCode != 0) {
                throw IllegalStateException(response.errorOutput.ifBlank { response.output })
            }
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "delete($path) failed: ${it.message}", it) }
        return result
    }

    suspend fun pullToCache(remotePath: String, localFile: File): Result<Unit> {
        val result = connectionManager.withDadb { dadb -> dadb.pull(localFile, remotePath) }
        result.exceptionOrNull()?.let { Log.e(TAG, "pullToCache($remotePath) failed: ${it.message}", it) }
        return result
    }

    suspend fun push(localFile: File, remotePath: String): Result<Unit> {
        val result = connectionManager.withDadb { dadb ->
            dadb.push(localFile, remotePath, "664".toInt(8), System.currentTimeMillis() / 1000)
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "push($remotePath) failed: ${it.message}", it) }
        return result
    }

    /**
     * Recursively searches [rootPath] for names containing [query] (case-
     * insensitive), via a single server-side `find` rather than N nested
     * `ls` calls.
     */
    suspend fun search(rootPath: String, query: String): Result<List<RemoteFile>> =
        findFiles(rootPath, "-iname '*${query.replace("'", "'\\''")}*'")

    /**
     * Recursively finds files under [rootPath] whose extension is in
     * [extensions] (used by the type-filter chips: Images/Videos/Documents).
     * When [matchNone] is true, [extensions] is treated as the set of known
     * extensions to *exclude* (the "Other" filter - anything not matching a
     * known type).
     */
    suspend fun searchByExtensions(rootPath: String, extensions: Set<String>, matchNone: Boolean = false): Result<List<RemoteFile>> {
        val expr = if (matchNone) {
            extensions.joinToString(" ") { "-not -iname '*.$it'" }
        } else {
            "\\( " + extensions.joinToString(" -o ") { "-iname '*.$it'" } + " \\)"
        }
        return findFiles(rootPath, expr)
    }

    private suspend fun findFiles(rootPath: String, findExpr: String): Result<List<RemoteFile>> {
        val result = connectionManager.withDadb { dadb ->
            val searchPath = if (rootPath.endsWith("/")) rootPath else "$rootPath/"
            // -printf emits size, mtime (epoch seconds, may have fractional
            // part) and path in one pass, so results carry real size/mtime
            // without a second round trip per match.
            val response = dadb.shell("find ${shellQuote(searchPath)} -type f $findExpr -printf '%s %T@ %p\\n'")
            if (response.exitCode != 0) {
                throw IllegalStateException(response.errorOutput.ifBlank { response.output })
            }
            response.output.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { parseFindLine(it) }
                .toList()
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "findFiles($rootPath) failed: ${it.message}", it) }
        return result
    }

    private fun parseFindLine(line: String): RemoteFile? {
        val parts = line.split(Regex("\\s+"), limit = 3)
        if (parts.size < 3) return null
        val size = parts[0].toLongOrNull() ?: 0L
        val epochSeconds = parts[1].substringBefore('.').toLongOrNull()
        val path = parts[2]
        return RemoteFile(
            name = path.substringAfterLast('/'),
            path = path,
            isDirectory = false,
            sizeBytes = size,
            modifiedAt = epochSeconds?.let { it * 1000 },
        )
    }

    /** Free/used/total space for the filesystem backing [path], via `df -h`. */
    suspend fun storageInfo(path: String): Result<StorageInfo> {
        val result = connectionManager.withDadb { dadb ->
            val response = dadb.shell("df -h ${shellQuote(path)}")
            if (response.exitCode != 0) {
                throw IllegalStateException(response.errorOutput.ifBlank { response.output })
            }
            val dataLine = response.output.lineSequence().drop(1).firstOrNull()?.trim()
                ?: throw IllegalStateException("Unexpected df output")
            val columns = dataLine.split(Regex("\\s+"))
            if (columns.size < 5) throw IllegalStateException("Unexpected df output")
            StorageInfo(total = columns[1], used = columns[2], available = columns[3], usedPercent = columns[4])
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "storageInfo($path) failed: ${it.message}", it) }
        return result
    }

    /** Total size of everything under [path], via `du -sh`. Can be slow on large trees. */
    suspend fun folderSize(path: String): Result<String> {
        val result = connectionManager.withDadb { dadb ->
            val listPath = if (path.endsWith("/")) path else "$path/"
            val response = dadb.shell("du -sh ${shellQuote(listPath)} 2>/dev/null")
            if (response.exitCode != 0) {
                throw IllegalStateException(response.errorOutput.ifBlank { response.output })
            }
            response.output.trim().split(Regex("\\s+")).firstOrNull() ?: "-"
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "folderSize($path) failed: ${it.message}", it) }
        return result
    }

    /** Checks a remote path exists, for upload-conflict detection before pushing. */
    suspend fun exists(path: String): Result<Boolean> {
        val result = connectionManager.withDadb { dadb ->
            val response = dadb.shell("[ -e ${shellQuote(path)} ] && echo yes || echo no")
            response.output.trim() == "yes"
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "exists($path) failed: ${it.message}", it) }
        return result
    }

    private fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun parseLsLine(line: String, parentPath: String): RemoteFile? {
        if (line.isBlank() || line.startsWith("total ")) return null

        val parts = line.trimEnd().split(Regex("\\s+"), limit = 8)
        if (parts.size < 8) return null

        val perms = parts[0]
        val sizeStr = parts[4]
        val date = parts[5]
        val time = parts[6]
        var name = parts[7]

        // Symlinks: "name -> target" - keep just the link name.
        val arrowIndex = name.indexOf(" -> ")
        if (arrowIndex >= 0) name = name.substring(0, arrowIndex)

        if (name == "." || name == "..") return null

        val isDirectory = perms.startsWith("d")
        val size = sizeStr.toLongOrNull() ?: 0L
        val modifiedAt = runCatching { dateFormat.parse("$date $time")?.time }.getOrNull()
        val fullPath = if (parentPath.endsWith("/")) "$parentPath$name" else "$parentPath/$name"

        return RemoteFile(
            name = name,
            path = fullPath,
            isDirectory = isDirectory,
            sizeBytes = size,
            modifiedAt = modifiedAt,
        )
    }
}
