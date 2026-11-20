package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * First-run / edit form for direct IPTV. Credentials are stored on device only.
 *
 * "Connect" checks the portal and the login against `player_api.php` before
 * saving, so a moved portal, a wrong password and an expired line each get
 * their own message here instead of an empty guide later.
 */
@Composable
internal fun XtreamSetupScreen(
    initial: XtreamAccount?,
    required: Boolean,
    onSave: (XtreamAccount) -> Unit,
    verify: suspend (XtreamAccount) -> XtreamAuth,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun candidate(): XtreamAccount {
        val cleaned = host.trim()
        val normalised = when {
            cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            else -> "http://$cleaned"
        }
        return XtreamAccount(
            host = normalised,
            username = username.trim(),
            password = password.trim(),
            output = initial?.output ?: OUTPUT_TS,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 420.dp)) {
            Text("IPTV setup", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Connect straight to your provider's Xtream panel. Credentials stay on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; error = null },
                label = { Text("Server URL") },
                placeholder = { Text("http://provider.example:8080") },
                singleLine = true,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; error = null },
                label = { Text("Username") },
                singleLine = true,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("Password") },
                singleLine = true,
                enabled = !checking,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            val message = error
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = !checking && host.isNotBlank() && username.isNotBlank(),
                    onClick = {
                        val account = candidate()
                        checking = true
                        error = null
                        scope.launch {
                            try {
                                val auth = verify(account)
                                onSave(account.copy(output = auth.preferredOutput))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Couldn't connect to the provider."
                            } finally {
                                checking = false
                            }
                        }
                    },
                ) { Text("Connect") }
                if (checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (error != null && !checking) {
                    // Lets a known-good login through while the provider is briefly down.
                    TextButton(onClick = { onSave(candidate()) }) { Text("Save anyway") }
                }
                if (onCancel != null && !checking) {
                    TextButton(onClick = onCancel) { Text(if (required) "Skip" else "Cancel") }
                }
            }
        }
    }
}
