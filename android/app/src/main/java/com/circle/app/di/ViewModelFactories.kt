package com.circle.app.di

import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.circle.app.domain.model.Account
import com.circle.app.presentation.detail.CircleDetailViewModel
import com.circle.app.presentation.discover.DiscoverViewModel
import com.circle.app.presentation.mycircles.MyCirclesViewModel
import kotlinx.coroutines.flow.flowOf

class ViewModelFactories(private val container: AppContainer) {
    fun onboarding(account: com.circle.app.domain.model.Account) = viewModelFactory {
        initializer {
            com.circle.app.presentation.onboarding.OnboardingViewModel(
                account,
                container.saveOnboarding,
                createSavedStateHandle(),
            )
        }
    }

    val session = viewModelFactory {
        initializer {
            com.circle.app.presentation.auth.SessionViewModel(
                checkNotNull(container.auth),
                container.getAccount,
                container.saveFirstName,
            )
        }
    }
    val auth = viewModelFactory {
        initializer {
            com.circle.app.presentation.auth.AuthViewModel(
                container.signIn,
                container.signUp,
                container.resetPassword,
                container.signInWithGoogle,
            )
        }
    }
    val create = viewModelFactory {
        initializer {
            com.circle.app.presentation.create.CreateCircleViewModel(
                container.createCircle,
                container.venues,
                createSavedStateHandle(),
                if (container.isDemo) null else container.eventActions,
                drafts = container.drafts(),
                getAccount = container.getAccount,
            )
        }
    }
    val member = viewModelFactory {
        initializer { com.circle.app.presentation.member.MemberViewModel(container.memberActions) }
    }
    val edit = viewModelFactory {
        initializer {
            val handle = createSavedStateHandle()
            com.circle.app.presentation.create.CreateCircleViewModel(
                container.createCircle,
                container.venues,
                handle,
                container.eventActions,
                container.getCircle,
                checkNotNull(handle.get<String>("circleId")),
                getAccount = container.getAccount,
            )
        }
    }
    val chat = viewModelFactory {
        initializer {
            val handle = createSavedStateHandle()
            com.circle.app.presentation.chat.ChatViewModel(
                checkNotNull(handle.get<String>("circleId")),
                container.chatActions,
                handle,
            )
        }
    }

    fun discover(account: com.circle.app.domain.model.Account?) = viewModelFactory {
        initializer {
            DiscoverViewModel(
                container.getNearbyCircles,
                container.areas,
                createSavedStateHandle(),
                account?.preferences,
                account?.let { container.observePreferences(it.id) }
                    ?: kotlinx.coroutines.flow.flowOf(null),
                if (container.isDemo) null else container.eventActions,
                if (container.isDemo) null else container.updateDiscoveryPreferences,
            )
        }
    }

    fun myCircles(account: Account? = null) = viewModelFactory {
        initializer {
            MyCirclesViewModel(
                container.getJoinedCircles,
                account?.preferences,
                account?.let { container.observePreferences(it.id) }
                    ?: flowOf(null),
            )
        }
    }
    val myCircles get() = myCircles(null)
    val safety = viewModelFactory {
        initializer {
            com.circle.app.presentation.safety.SafetyViewModel(
                container.getCircle,
                container.blockUser,
                createSavedStateHandle(),
            )
        }
    }
    val blockedUsers = viewModelFactory {
        initializer {
            com.circle.app.presentation.safety.BlockedUsersViewModel(
                container.getBlockedUsers,
                container.unblockUser,
            )
        }
    }
    val detail = viewModelFactory {
        initializer {
            val handle = createSavedStateHandle()
            CircleDetailViewModel(
                checkNotNull(handle.get<String>("circleId")),
                container.getCircle,
                container.updateMembership,
                container.eventActions,
            )
        }
    }
}
