package com.circle.app.presentation.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circle.app.domain.model.*
import com.circle.app.domain.usecase.*
import com.circle.app.presentation.common.toUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(val draft: OnboardingDraft, val step: Int = 0,
    val saving: Boolean = false, val error: String? = null, val savedAccount: Account? = null)

class OnboardingViewModel(account: Account, private val saveOnboarding: SaveOnboarding,
    private val saved: SavedStateHandle) : ViewModel() {
    private val previous=account.preferences
    private val mutable=MutableStateFlow(OnboardingUiState(OnboardingDraft(
        firstName=saved["name"] ?: account.firstName.takeIf { account.profileComplete }.orEmpty(),
        adultConfirmed=saved["adult"] ?: (previous?.adultConfirmed==true),
        termsAccepted=saved["terms"] ?: (previous?.termsVersion==COMMUNITY_TERMS_VERSION),
        interests=saved.get<ArrayList<String>>("interests")?.toList() ?: previous?.interests.orEmpty(),
        areaName=saved["area"] ?: previous?.areaName.orEmpty(), radiusKm=saved["radius"] ?: previous?.radiusKm ?: 5,
        birthDate=saved["birthDate"] ?: previous?.birthDate.orEmpty(), gender=saved["gender"] ?: previous?.gender.orEmpty(),
        latitude=saved.get<Double>("latitude") ?: previous?.latitude,
        longitude=saved.get<Double>("longitude") ?: previous?.longitude,
    ), step=(saved.get<Int>("step") ?: 0).coerceIn(0,2)))
    val state=mutable.asStateFlow()
    private fun edit(change: (OnboardingDraft)->OnboardingDraft) {
        if (state.value.saving || state.value.savedAccount!=null) return
        val d=change(state.value.draft);mutable.value=state.value.copy(draft=d,error=null)
        saved["name"]=d.firstName;saved["adult"]=d.adultConfirmed;saved["terms"]=d.termsAccepted
        saved["interests"]=ArrayList(d.interests);saved["area"]=d.areaName;saved["radius"]=d.radiusKm
        saved["birthDate"]=d.birthDate;saved["gender"]=d.gender;saved["latitude"]=d.latitude;saved["longitude"]=d.longitude
    }
    fun name(value:String)=edit { it.copy(firstName=value.take(60)) }
    fun birthDate(value:String)=edit { it.copy(birthDate=value) }
    fun gender(value:String)=edit { if(value in PreferenceOptions.genders) it.copy(gender=value) else it }
    fun adult(value:Boolean)=edit { it.copy(adultConfirmed=value) }
    fun terms(value:Boolean)=edit { it.copy(termsAccepted=value) }
    fun area(value:String)=edit { it.copy(areaName=value.take(120)) }
    fun location(value:Area)=edit { it.copy(areaName=value.name.take(120),latitude=value.latitude,longitude=value.longitude) }
    fun radius(value:Int)=edit { it.copy(radiusKm=value.coerceIn(1,10)) }
    fun interest(value:String)=edit { d -> if(value !in PreferenceOptions.interests) d else d.copy(
        interests=if(value in d.interests) d.interests-value else d.interests+value) }
    fun back() { if(state.value.saving || state.value.step==0)return
        mutable.value=state.value.copy(step=state.value.step-1,error=null);saved["step"]=state.value.step }
    fun next() {
        val s=state.value;if(s.saving || s.savedAccount!=null)return
        onboardingIssue(s.draft,s.step)?.let { mutable.value=s.copy(error=it.message());return }
        if(s.step<2) { mutable.value=s.copy(step=s.step+1,error=null);saved["step"]=state.value.step;return }
        mutable.value=s.copy(saving=true,error=null)
        viewModelScope.launch {
            try {
                val account=saveOnboarding(s.draft)
                if (!account.onboardingComplete || account.preferences?.valid()!=true)
                    throw com.circle.app.domain.error.CircleException(com.circle.app.domain.error.FailureReason.INVALID_RESPONSE)
                mutable.value=state.value.copy(saving=false,savedAccount=account)
            }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { mutable.value=state.value.copy(saving=false,error=if(e is OnboardingException) e.issue.message() else e.toUiMessage()) }
        }
    }
}
private fun OnboardingIssue.message()=when(this) {
    OnboardingIssue.NAME -> "Enter your first name."
    OnboardingIssue.AGE -> "Enter your date of birth and confirm you are 18 or older. Ages 18–100 are supported."
    OnboardingIssue.GENDER -> "Choose your gender, or prefer not to say."
    OnboardingIssue.TERMS -> "Read and accept the community guidelines."
    OnboardingIssue.INTERESTS -> "Choose at least one interest."
    OnboardingIssue.AREA -> "Use your location or select your area on the map."
    OnboardingIssue.RADIUS -> "Choose a radius from 1 to 10 km."
}
