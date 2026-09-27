package com.circle.app.presentation.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.circle.app.R
import com.circle.app.domain.error.AuthException
import com.circle.app.domain.error.AuthFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AuthRoute(
    vm: AuthViewModel,
    google: GoogleSignInLauncher,
    testing: Boolean,
    sessionMessage: String?,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AuthScreen(
        state,
        testing,
        google.available,
        sessionMessage,
        vm::email,
        vm::password,
        vm::confirmation,
        vm::mode,
        vm::submit,
        onGoogle = {
            if (vm.beginGoogle())
                scope.launch {
                    try {
                        val activity =
                            context.activity()
                                ?: throw AuthException(AuthFailure.GOOGLE_UNAVAILABLE)
                        vm.finishGoogle(google.launch(activity))
                    } catch (e: CancellationException) {
                        vm.finishGoogle(null)
                        throw e
                    } catch (e: Exception) {
                        vm.failed(e)
                    }
                }
        },
    )
}

private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

private val FieldShape = RoundedCornerShape(14.dp)
private val ButtonShape = RoundedCornerShape(16.dp)

@Composable
fun AuthScreen(
    state: AuthUiState,
    testing: Boolean,
    googleAvailable: Boolean,
    sessionMessage: String?,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConfirmation: (String) -> Unit,
    onMode: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    onGoogle: () -> Unit,
) {
    val reset = state.mode == AuthMode.RESET
    val signup = state.mode == AuthMode.SIGNUP
    val focusManager = LocalFocusManager.current

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors =
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                            MaterialTheme.colorScheme.background,
                        )
                )
            )
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // App Logo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background)
                    )
                }
                Text(
                    "circle",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (testing)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    color = Color.Transparent,
                ) {
                    Text(
                        "LOCAL ACCOUNT TESTING",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

            AnimatedContent(
                targetState = reset to signup,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "headline",
            ) { (isReset, isSignup) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (isReset) "Reset your password"
                        else if (isSignup) "Find your people." else "Welcome to Circle.",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        if (isReset) "Enter your email to request a password reset link."
                        else "Good plans. Better company. One activity at a time.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!reset)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !signup,
                        onClick = { onMode(AuthMode.LOGIN) },
                        enabled = !state.busy,
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("Log in")
                    }
                    SegmentedButton(
                        selected = signup,
                        onClick = { onMode(AuthMode.SIGNUP) },
                        enabled = !state.busy,
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text("Sign up")
                    }
                }

            AnimatedVisibility(
                visible = sessionMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                sessionMessage?.let {
                    StatusRow(it, MaterialTheme.colorScheme.error, Icons.Filled.ErrorOutline)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmail,
                    label = { Text("Email address") },
                    singleLine = true,
                    enabled = !state.busy,
                    shape = FieldShape,
                    leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = if (reset) ImeAction.Done else ImeAction.Next,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            onDone = {
                                focusManager.clearFocus()
                                onSubmit()
                            },
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!reset) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PasswordField(
                            value = state.password,
                            onChange = onPassword,
                            label = "Password",
                            enabled = !state.busy,
                            imeAction = if (signup) ImeAction.Next else ImeAction.Done,
                            onImeAction = {
                                if (signup) focusManager.moveFocus(FocusDirection.Down)
                                else {
                                    focusManager.clearFocus()
                                    onSubmit()
                                }
                            },
                        )
                        if (signup) {
                            Text(
                                "Use at least 8 characters. A longer passphrase works well.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            PasswordField(
                                value = state.confirmation,
                                onChange = onConfirmation,
                                label = "Confirm password",
                                enabled = !state.busy,
                                imeAction = ImeAction.Done,
                                onImeAction = {
                                    focusManager.clearFocus()
                                    onSubmit()
                                },
                            )
                        } else {
                            TextButton(
                                onClick = { onMode(AuthMode.RESET) },
                                enabled = !state.busy,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("Forgot password?")
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                state.error?.let {
                    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        StatusRow(it, MaterialTheme.colorScheme.error, Icons.Filled.ErrorOutline)
                    }
                }
            }

            AnimatedVisibility(
                visible = state.notice != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                state.notice?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Row(
                                Modifier
                                    .padding(16.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(it, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                        if (testing)
                            Text(
                                "The reset link appears in your local Firebase emulator terminal.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }
            }

            Button(
                onSubmit,
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                enabled = !state.busy,
                shape = ButtonShape,
            ) {
                if (state.busy)
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                else
                    Text(
                        if (reset) "Send reset link" else if (signup) "Create account" else "Log in"
                    )
            }

            if (reset) {
                TextButton(
                    onClick = { onMode(AuthMode.LOGIN) },
                    enabled = !state.busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Back to log in")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HorizontalDivider(Modifier.weight(1f))
                        Text(
                            "or",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.weight(1f))
                    }
                    OutlinedButton(
                        onGoogle,
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        enabled = !state.busy && googleAvailable,
                        shape = RoundedCornerShape(26.dp),
                        border = BorderStroke(1.dp, Color(0xFF747775)),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Image(
                            painterResource(R.drawable.ic_google),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Sign in with Google")
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!googleAvailable) {
                            Text(
                                if (testing)
                                    "Email accounts work locally. Google sign-in needs your Firebase project configuration."
                                else "Google sign-in is not configured in this build.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            "Your email stays private. Your circles belong to your account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun StatusRow(message: String, color: Color, icon: ImageVector) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(message, color = color)
    }
}

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        shape = FieldShape,
        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
    )
}