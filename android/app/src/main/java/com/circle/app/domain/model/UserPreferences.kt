package com.circle.app.domain.model

import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset

const val COMMUNITY_TERMS_VERSION = "community-v1"
object PreferenceOptions {
    val interests = listOf("coffee", "outdoors", "games", "fitness")
    val genders = listOf("male", "female", "other", "prefer_not_to_say")
    val audiences = listOf("everyone", "male", "female")
}
fun ageOn(birthDate: String, day: LocalDate = LocalDate.now(ZoneOffset.UTC)): Int? =
    runCatching { Period.between(LocalDate.parse(birthDate), day).years }.getOrNull()
fun validCoordinates(latitude: Double?, longitude: Double?) = latitude != null && longitude != null &&
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
fun validCategory(value: String) = value == value.trim() && value.length in 1..40 &&
    value.all { it.isLetterOrDigit() || Character.getType(it) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt()) || it in " -&'" }
fun genderLabel(value: String) = when (value) {
    "male" -> "Male"; "female" -> "Female"; "other" -> "Another gender"
    "prefer_not_to_say" -> "Prefer not to say"; else -> "Choose gender"
}
fun audienceLabel(value: String) = when (value) { "male" -> "Male only"; "female" -> "Female only"; else -> "Everyone" }

data class UserPreferences(
    val interests: List<String>, val areaName: String, val radiusKm: Int,
    val adultConfirmed: Boolean, val termsVersion: String,
    val birthDate: String = "", val gender: String = "",
    val latitude: Double? = null, val longitude: Double? = null,
) {
    fun valid() = adultConfirmed && termsVersion == COMMUNITY_TERMS_VERSION &&
        areaName.isNotBlank() && areaName.length <= 120 && validCoordinates(latitude, longitude) &&
        ageOn(birthDate)?.let { it in 18..100 } == true && gender in PreferenceOptions.genders &&
        radiusKm in 1..10 && interests.size in 1..4 && interests.distinct().size == interests.size &&
        interests.all { it in PreferenceOptions.interests }
    fun area(): Area? = if (validCoordinates(latitude, longitude)) Area(areaName, latitude!!, longitude!!) else null
    fun draft(firstName: String) = OnboardingDraft(firstName, adultConfirmed, termsVersion == COMMUNITY_TERMS_VERSION,
        interests, areaName, radiusKm, birthDate, gender, latitude, longitude)
}
data class OnboardingDraft(
    val firstName: String = "", val adultConfirmed: Boolean = false,
    val termsAccepted: Boolean = false, val interests: List<String> = emptyList(),
    val areaName: String = "", val radiusKm: Int = 5,
    val birthDate: String = "", val gender: String = "",
    val latitude: Double? = null, val longitude: Double? = null,
)
