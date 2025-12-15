package com.example.aichallenge.user

data class UserProfilesState(
    val profiles: List<UserProfile> = emptyList(),
    val selectedUserId: String? = null,
) {
    val selectedProfile: UserProfile?
        get() = profiles.firstOrNull { it.id == selectedUserId }

    val hasProfiles: Boolean
        get() = profiles.isNotEmpty()
}
