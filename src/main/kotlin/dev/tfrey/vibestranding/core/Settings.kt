package dev.tfrey.vibestranding.core

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Project-level persistent settings for Vibe Stranding. Stored in the
 * project's `.idea/vibe-stranding.xml` so per-project overrides are possible
 * (e.g. on for the main coding repo, off for a sandbox).
 *
 * Settings are exposed for read/write over the plugin's MCP / REST surface
 * via [FIELDS] — adding a new field to [State] plus one entry there is enough
 * to make it remotely controllable. The Settings-UI Configurable still binds
 * to the `var` accessors directly.
 */
@Service(Service.Level.PROJECT)
@State(name = "VibeStranding", storages = [Storage("vibe-stranding.xml")])
class Settings : PersistentStateComponent<Settings.State> {

    data class State(var resumeStrandsOnStartup: Boolean = true, var descriptionDelayMinutes: Int = 5)

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    var resumeStrandsOnStartup: Boolean
        get() = state.resumeStrandsOnStartup
        set(value) {
            state = state.copy(resumeStrandsOnStartup = value)
        }

    var descriptionDelayMinutes: Int
        get() = state.descriptionDelayMinutes
        set(value) {
            state = state.copy(descriptionDelayMinutes = value)
        }

    /** JSON object keyed by [FIELDS] entry names, matching the MCP `settings` tool response shape. */
    fun snapshot(): JsonObject = buildJsonObject {
        FIELDS.forEach { put(it.name, it.read(this@Settings)) }
    }

    /**
     * Validate every key in [updates] first; apply nothing if any fail. Keeps
     * partial-update weirdness out of the persistent state when an MCP caller
     * passes a typo'd key or a bad value.
     */
    fun applyUpdates(updates: Map<String, String>): List<String> {
        val errors = mutableListOf<String>()
        val staged = mutableListOf<() -> Unit>()
        for ((key, raw) in updates) {
            val field = FIELDS.firstOrNull { it.name == key }
            if (field == null) {
                errors += "$key: unknown setting"
                continue
            }
            val apply = field.stageFromString(this, raw)
            if (apply == null) {
                errors += "$key: expected ${field.jsonType}, got '$raw'"
            } else {
                staged += apply
            }
        }
        if (errors.isEmpty()) staged.forEach { it() }
        return errors
    }

    class Field(
        val name: String,
        val jsonType: String,
        val description: String,
        val read: (Settings) -> JsonElement,
        // null = raw value won't coerce; otherwise a deferred apply so the
        // caller can validate-all-then-apply-all.
        val stageFromString: (Settings, String) -> (() -> Unit)?,
    )

    companion object {
        fun get(project: Project): Settings = project.getService(Settings::class.java)

        /** Add an entry here to expose a new setting over MCP/REST. */
        val FIELDS: List<Field> = listOf(
            Field(
                name = "resumeStrandsOnStartup",
                jsonType = "boolean",
                description = "If true, every strand whose worktree still exists is auto-resumed when " +
                    "this project opens — its terminal tab is restored running `claude --continue`. Default true.",
                read = { JsonPrimitive(it.resumeStrandsOnStartup) },
                stageFromString = { settings, raw ->
                    raw.toBooleanStrictOrNull()?.let { value -> { settings.resumeStrandsOnStartup = value } }
                },
            ),
            Field(
                name = "descriptionDelayMinutes",
                jsonType = "integer",
                description = "Minutes to wait after a strand is created before the background job asks " +
                    "the LLM for a one-sentence description of what the strand is about. Strands with no " +
                    "diff when the timer fires reschedule for the same interval. Default 5; must be >= 1.",
                read = { JsonPrimitive(it.descriptionDelayMinutes) },
                stageFromString = { settings, raw ->
                    raw.toIntOrNull()?.takeIf { it >= 1 }
                        ?.let { value -> { settings.descriptionDelayMinutes = value } }
                },
            ),
        )
    }
}
