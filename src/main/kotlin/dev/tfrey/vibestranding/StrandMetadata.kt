package dev.tfrey.vibestranding

import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-strand state that needs to outlive the live terminal tab — currently
 * the emoji picked at create time (so resume can rebuild the original tab
 * label) and the free-text description the user typed, kept around for
 * future use (re-priming the strand session, surfacing in lists, etc.).
 */
data class StrandMeta(val emoji: String, val description: String? = null)

/**
 * Backend-agnostic storage for [StrandMeta]. All plugin code reads/writes
 * metadata through this interface so the underlying store (sidecar file,
 * plugin-level PersistentStateComponent, remote service, ...) can be
 * swapped without touching the callers.
 *
 * All methods are best-effort: metadata is a nice-to-have, never load-bearing.
 *  - [read] returns null when nothing is stored or the stored data is corrupt.
 *  - [write] and [delete] swallow failures (and log them) rather than throwing.
 */
interface StrandMetadataStore {
    fun read(strand: String): StrandMeta?
    fun write(strand: String, meta: StrandMeta)
    fun delete(strand: String)
}

/**
 * Stores each strand's metadata as a JSON sidecar inside the strand's
 * per-worktree gitdir (typically `<main>/.git/worktrees/<strand>/vibe-stranding.json`).
 *
 * Why there: `git worktree remove` deletes that directory wholesale, so the
 * sidecar gets cleaned up for free on the normal teardown path. Living outside
 * the working tree also means the file never shows up in `git status` and
 * never needs an `info/exclude` entry.
 *
 * The store delegates path resolution to [sidecarPath] so it doesn't need to
 * know how to shell out to git itself; callers (i.e. [GitStrands]) own that.
 */
class WorktreeSidecarStore(private val sidecarPath: (strand: String) -> Path?) : StrandMetadataStore {

    override fun read(strand: String): StrandMeta? {
        val path = sidecarPath(strand) ?: return null
        if (!Files.exists(path)) return null
        return try {
            val obj = JSON.parseToJsonElement(Files.readString(path)).jsonObject
            val emoji = obj["emoji"]?.jsonPrimitive?.contentOrNull ?: return null
            val description = obj["description"]?.jsonPrimitive?.contentOrNull
            StrandMeta(emoji, description)
        } catch (t: Throwable) {
            LOG.warn("Could not parse strand metadata at $path", t)
            null
        }
    }

    override fun write(strand: String, meta: StrandMeta) {
        val path = sidecarPath(strand) ?: return
        val body = JSON.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("emoji", meta.emoji)
                if (meta.description != null) put("description", meta.description)
            },
        )
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, body)
        } catch (t: Throwable) {
            LOG.warn("Could not write strand metadata to $path", t)
        }
    }

    override fun delete(strand: String) {
        val path = sidecarPath(strand) ?: return
        try {
            Files.deleteIfExists(path)
        } catch (t: Throwable) {
            LOG.warn("Could not delete strand metadata at $path", t)
        }
    }

    private companion object {
        private val LOG = logger<WorktreeSidecarStore>()
        private val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}
