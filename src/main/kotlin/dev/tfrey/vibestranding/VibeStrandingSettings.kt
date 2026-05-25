package dev.tfrey.vibestranding

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Project-level persistent settings for Vibe Stranding. Stored in the
 * project's `.idea/vibe-stranding.xml` so per-project overrides are possible
 * (e.g. on for the main coding repo, off for a sandbox).
 */
@Service(Service.Level.PROJECT)
@State(name = "VibeStranding", storages = [Storage("vibe-stranding.xml")])
class VibeStrandingSettings : PersistentStateComponent<VibeStrandingSettings.State> {

    data class State(var resumeStrandsOnStartup: Boolean = true)

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

    companion object {
        fun get(project: Project): VibeStrandingSettings = project.getService(VibeStrandingSettings::class.java)
    }
}
