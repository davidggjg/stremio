package com.stremio.lite.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stremio.lite.ui.theme.GlassBackground
import com.stremio.lite.ui.theme.Palette
import com.stremio.lite.ui.AuthViewModel

@Composable
fun AccountScreen() {
    val viewModel: AuthViewModel = viewModel()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Stremio account", style = MaterialTheme.typography.headlineSmall)

        val current = session
        if (current != null) {
            Text(
                current.user?.email ?: "Signed in",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                if (current.user?.isPremium == true) "Premium account" else "Free account",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Your addon collection and library are pulled from api.strem.io and stay in sync with " +
                    "web.stremio.com and the desktop app.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            OutlinedButton(onClick = { viewModel.logout() }, modifier = Modifier.padding(top = 16.dp)) {
                Text("Sign out")
            }
            return@Column
        }

        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var authKey by remember { mutableStateOf("") }
        var showAuthKey by remember { mutableStateOf(false) }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        Button(
            onClick = { viewModel.login(email, password) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text(if (state.loading) "Signing in…" else "Sign in")
        }
        OutlinedButton(
            onClick = { viewModel.register(email, password) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Create an account")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        TextButton(onClick = { showAuthKey = !showAuthKey }) {
            Text(if (showAuthKey) "Hide authKey sign-in" else "Sign in with an authKey instead")
        }
        if (showAuthKey) {
            Text(
                "On web.stremio.com the authKey is stored in localStorage under \"profile\". Paste it here " +
                    "to sign in without typing your password.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = authKey,
                onValueChange = { authKey = it },
                label = { Text("authKey") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Button(
                onClick = { viewModel.loginWithAuthKey(authKey) },
                enabled = !state.loading,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Use authKey")
            }
        }
    }
}
