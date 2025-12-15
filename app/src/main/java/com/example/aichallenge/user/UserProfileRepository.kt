package com.example.aichallenge.user

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object UserProfileRepository {
    private const val TAG = "UserProfileRepo"
    private val lock = Any()
    private val _state = MutableStateFlow(UserProfilesState())
    val state: StateFlow<UserProfilesState> = _state
    private var initialized = false
    private lateinit var storageFile: File

    fun initialize(context: Context) {
        if (initialized) return
        storageFile = File(context.filesDir, "user_profiles.json")
        loadFromDisk()
        initialized = true
    }

    fun createNewProfile(): UserProfile = UserProfile.create()

    fun currentProfile(): UserProfile? = _state.value.selectedProfile

    fun currentState(): UserProfilesState = _state.value

    fun upsert(profile: UserProfile) {
        ensureInitialized()
        updateState { prev ->
            val mutable = prev.profiles.toMutableList()
            val idx = mutable.indexOfFirst { it.id == profile.id }
            if (idx >= 0) {
                mutable[idx] = profile
            } else {
                mutable.add(profile)
            }
            val selectedId = prev.selectedUserId ?: profile.id
            prev.copy(
                profiles = mutable.toList(),
                selectedUserId = selectedId.takeIf { id -> mutable.any { it.id == id } } ?: profile.id
            )
        }
    }

    fun selectUser(id: String) {
        ensureInitialized()
        updateState { prev ->
            if (prev.selectedUserId == id) {
                prev
            } else if (prev.profiles.any { it.id == id }) {
                prev.copy(selectedUserId = id)
            } else {
                prev
            }
        }
    }

    fun deleteUser(id: String) {
        ensureInitialized()
        updateState { prev ->
            val filtered = prev.profiles.filterNot { it.id == id }
            val selectedId = prev.selectedUserId
            val newSelected = when {
                filtered.isEmpty() -> null
                selectedId == id -> filtered.first().id
                selectedId != null && filtered.any { it.id == selectedId } -> selectedId
                else -> filtered.first().id
            }
            prev.copy(
                profiles = filtered,
                selectedUserId = newSelected
            )
        }
    }

    private fun ensureInitialized() {
        check(initialized) { "UserProfileRepository is not initialized" }
    }

    private fun updateState(transform: (UserProfilesState) -> UserProfilesState) {
        val newState: UserProfilesState
        synchronized(lock) {
            val updated = transform(_state.value)
            if (updated == _state.value) {
                return
            }
            _state.value = updated
            newState = updated
        }
        persistState(newState)
    }

    private fun persistState(state: UserProfilesState) {
        runCatching {
            storageFile.parentFile?.mkdirs()
            val arr = JSONArray()
            state.profiles.forEach { profile ->
                arr.put(profile.toJson())
            }
            val obj = JSONObject()
                .put("selectedUserId", state.selectedUserId)
                .put("profiles", arr)
            storageFile.writeText(obj.toString())
        }.onFailure { error ->
            Log.e(TAG, "Failed to persist user profiles", error)
        }
    }

    private fun loadFromDisk() {
        if (!storageFile.exists()) return
        runCatching {
            val raw = storageFile.readText()
            if (raw.isBlank()) return@runCatching
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("profiles") ?: JSONArray()
            val profiles = mutableListOf<UserProfile>()
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                profiles.add(UserProfile.fromJson(entry))
            }
            val selectedId = obj.optString("selectedUserId").takeIf { it.isNotBlank() }
                ?.takeIf { id -> profiles.any { it.id == id } }
            _state.value = UserProfilesState(
                profiles = profiles,
                selectedUserId = selectedId ?: profiles.firstOrNull()?.id
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to load user profiles", error)
        }
    }
}
