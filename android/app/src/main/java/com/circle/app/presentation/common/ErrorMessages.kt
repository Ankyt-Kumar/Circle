package com.circle.app.presentation.common

import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason

fun Throwable.toUiMessage(): String =
    when ((this as? CircleException)?.reason) {
        FailureReason.INELIGIBLE -> "Your age or gender does not meet this circle’s requirements. Hosts must also qualify; edits must include all current members."
        FailureReason.USER_BLOCKED -> "This circle is unavailable, or you no longer have access."
        FailureReason.RATE_LIMIT -> "Too many requests. Please wait and try again."
        FailureReason.INVALID_INPUT ->
            "Check the form: use a future time within 7 days, a public venue, and 4–8 people."
        FailureReason.SIGN_IN_REQUIRED -> "Please sign in again."
        FailureReason.ACCOUNT_BLOCKED -> "Your account is unavailable."
        FailureReason.ONBOARDING_REQUIRED ->
            "Complete your preferences before joining or creating a circle."
        FailureReason.SERVICE_UNAVAILABLE ->
            "This feature is unavailable or its AI quota is exhausted. Try again later."
        FailureReason.NETWORK ->
            "Couldn’t reach Circle. Check that the local API is running, then retry."
        FailureReason.NOT_FOUND -> "This circle is no longer available."
        FailureReason.CONFLICT ->
            "This action is unavailable in the circle’s current state. Reload for its latest status."
        FailureReason.INVALID_RESPONSE -> "Couldn’t read the server response. Please retry."
        else -> "Something went wrong. Please retry."
    }
