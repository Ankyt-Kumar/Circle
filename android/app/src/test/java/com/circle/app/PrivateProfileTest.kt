package com.circle.app

import com.circle.app.domain.model.*
import com.circle.app.domain.usecase.*
import com.circle.app.domain.repository.AccountRepository
import com.circle.app.data.mapper.toJson
import com.circle.app.data.mapper.toPreferences
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PrivateProfileTest {
    @Test fun agesAreCalculatedAtTheBirthdayBoundary() {
        val date = LocalDate.of(2026,9,19)
        assertEquals(18, ageOn("2008-09-19",date))
        assertEquals(17, ageOn("2008-09-20",date))
        assertNull(ageOn("2000-02-30",date))
    }
    @Test fun profileRoundTripsCoordinatesAndRequiresEveryPrivateField() {
        val value = UserPreferences(listOf("coffee"),"My selected area",5,true,COMMUNITY_TERMS_VERSION,
            "1995-06-15","other",12.971234,77.641234)
        assertEquals(value, value.toJson().toPreferences())
        assertTrue(value.valid())
        assertFalse(value.copy(birthDate="").valid())
        assertFalse(value.copy(gender="").valid())
        assertFalse(value.copy(latitude=null).valid())
        assertFalse(value.copy(longitude=Double.NaN).valid())
        assertTrue(value.copy(gender="prefer_not_to_say").valid())
    }
    @Test fun customCategoriesHaveUsefulBounds() {
        assertTrue(validCategory("Book club"))
        assertTrue(validCategory("Chess & coffee"))
        assertFalse(validCategory("  "))
        assertFalse(validCategory("x".repeat(41)))
        assertFalse(validCategory("bad/category"))
    }
    @Test fun updatingLocationPreservesProfileAndUsesServerSave() = runTest {
        val preferences = UserPreferences(listOf("games"),"Old area",3,true,COMMUNITY_TERMS_VERSION,
            "1995-06-15","female",12.9,77.6)
        var submitted: Pair<Area,Int>? = null
        val account = Account("member","Maya",true,"password",onboardingComplete=true,preferences=preferences)
        val repo = object: AccountRepository {
            override suspend fun getAccount() = error("Location changes must not fetch and rewrite the full profile")
            override suspend fun saveFirstName(name:String) = error("Unused")
            override fun observePreferences(accountId:String) = flowOf(preferences)
            override suspend fun savePreferences(draft:OnboardingDraft): Account = error("Full profile must not be sent")
            override suspend fun saveLocation(area:Area,radiusKm:Int): Account { submitted=area to radiusKm;return account }
        }
        UpdateDiscoveryPreferences(repo)(Area("New area",13.01,77.72),5)
        assertEquals(Area("New area",13.01,77.72),submitted!!.first)
        assertEquals(5,submitted!!.second)
    }
}
