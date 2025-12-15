package com.example.aichallenge.user

import org.json.JSONObject
import java.util.UUID

data class UserProfile(
    val id: String,
    val name: String,
    val preferredLanguage: String,
    val country: String,
    val city: String,
    val timezone: String,
    val answerTone: String,
    val answerFormat: String,
    val answerLength: String,
    val expertiseDomain: String,
    val interests: String,
    val notes: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("preferredLanguage", preferredLanguage)
        .put("country", country)
        .put("city", city)
        .put("timezone", timezone)
        .put("answerTone", answerTone)
        .put("answerFormat", answerFormat)
        .put("answerLength", answerLength)
        .put("expertiseDomain", expertiseDomain)
        .put("interests", interests)
        .put("notes", notes)

    companion object {
        fun create(): UserProfile = UserProfile(
            id = UUID.randomUUID().toString(),
            name = "",
            preferredLanguage = "",
            country = "",
            city = "",
            timezone = "",
            answerTone = "",
            answerFormat = "",
            answerLength = "",
            expertiseDomain = "",
            interests = "",
            notes = "",
        )

        fun fromJson(json: JSONObject): UserProfile = UserProfile(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = json.optString("name"),
            preferredLanguage = json.optString("preferredLanguage"),
            country = json.optString("country"),
            city = json.optString("city"),
            timezone = json.optString("timezone"),
            answerTone = json.optString("answerTone"),
            answerFormat = json.optString("answerFormat"),
            answerLength = json.optString("answerLength"),
            expertiseDomain = json.optString("expertiseDomain"),
            interests = json.optString("interests"),
            notes = json.optString("notes"),
        )
    }
}
