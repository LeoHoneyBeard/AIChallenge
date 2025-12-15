package com.example.aichallenge.user

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class UserProfilesViewModel : ViewModel() {
    val state: StateFlow<UserProfilesState> = UserProfileRepository.state

    fun selectUser(id: String) {
        UserProfileRepository.selectUser(id)
    }

    fun saveProfile(profile: UserProfile) {
        UserProfileRepository.upsert(profile)
    }

    fun deleteProfile(profileId: String) {
        UserProfileRepository.deleteUser(profileId)
    }

    fun newProfile(): UserProfile = UserProfileRepository.createNewProfile()
}
