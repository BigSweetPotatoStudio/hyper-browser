package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.gecko.GeckoAuthPromptRequest

@Composable
internal fun AuthPromptDialog(
    request: GeckoAuthPromptRequest,
    onFinished: () -> Unit
) {
    var username by remember(request) { mutableStateOf(request.username) }
    var password by remember(request) { mutableStateOf(request.password) }

    fun dismiss() {
        request.dismiss()
        onFinished()
    }

    fun confirm() {
        request.confirm(username, password)
        onFinished()
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = {
            Text(
                text = request.title.ifBlank { stringResource(R.string.prompt_title_login_required) },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                val description = request.message.ifBlank { request.uri }
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_label_username)) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_label_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::confirm) {
                Text(stringResource(R.string.prompt_action_login))
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text(stringResource(R.string.prompt_action_cancel))
            }
        }
    )
}
