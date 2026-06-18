package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.gecko.GeckoColorPromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoChoicePromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoConfirmPromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoDateTimePromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoPromptRequest
import com.dadigua.hyperbrowser.gecko.GeckoTextPromptRequest
import org.mozilla.geckoview.GeckoSession

@Composable
internal fun GeckoPromptDialog(
    prompt: GeckoPromptRequest,
    onFinished: () -> Unit
) {
    when (prompt) {
        is GeckoPromptRequest.Alert -> AlertPromptDialog(prompt.request, onFinished)
        is GeckoPromptRequest.Button -> ButtonPromptDialog(prompt.request, onFinished)
        is GeckoPromptRequest.Text -> TextPromptDialog(prompt.request, onFinished)
        is GeckoPromptRequest.Choice -> ChoicePromptDialog(prompt.request, onFinished)
        is GeckoPromptRequest.Confirm -> ConfirmPromptDialog(prompt.request, onFinished)
        is GeckoPromptRequest.Color -> ColorPromptDialog(prompt.request, onFinished)
        is GeckoPromptRequest.DateTime -> DateTimePromptDialog(prompt.request, onFinished)
    }
}

@Composable
private fun AlertPromptDialog(
    request: com.dadigua.hyperbrowser.gecko.GeckoAlertPromptRequest,
    onFinished: () -> Unit
) {
    fun finish() {
        request.dismiss()
        onFinished()
    }
    AlertDialog(
        onDismissRequest = ::finish,
        title = { PromptTitle(request.title.ifBlank { stringResource(R.string.prompt_title_message) }) },
        text = { PromptMessage(request.message) },
        confirmButton = {
            TextButton(onClick = ::finish) {
                Text(stringResource(R.string.prompt_action_ok))
            }
        }
    )
}

@Composable
private fun ButtonPromptDialog(
    request: com.dadigua.hyperbrowser.gecko.GeckoButtonPromptRequest,
    onFinished: () -> Unit
) {
    fun dismiss() {
        request.dismiss()
        onFinished()
    }
    fun confirm(button: Int) {
        request.confirm(button)
        onFinished()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { PromptTitle(request.title.ifBlank { stringResource(R.string.prompt_title_confirm) }) },
        text = { PromptMessage(request.message) },
        confirmButton = {
            TextButton(onClick = { confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE) }) {
                Text(stringResource(R.string.prompt_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE) }) {
                Text(stringResource(R.string.prompt_action_cancel))
            }
        }
    )
}

@Composable
private fun TextPromptDialog(
    request: GeckoTextPromptRequest,
    onFinished: () -> Unit
) {
    var value by remember(request) { mutableStateOf(request.defaultValue) }
    fun dismiss() {
        request.dismiss()
        onFinished()
    }
    fun confirm() {
        request.confirm(value)
        onFinished()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { PromptTitle(request.title.ifBlank { stringResource(R.string.prompt_title_input) }) },
        text = {
            Column {
                PromptMessage(request.message)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::confirm) {
                Text(stringResource(R.string.prompt_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text(stringResource(R.string.prompt_action_cancel))
            }
        }
    )
}

@Composable
private fun ChoicePromptDialog(
    request: GeckoChoicePromptRequest,
    onFinished: () -> Unit
) {
    val selectedIds = remember(request) {
        mutableStateListOf<String>().also { list ->
            list.addAll(request.choices.filter { it.selected }.map { it.id })
        }
    }
    fun dismiss() {
        request.dismiss()
        onFinished()
    }
    fun confirm() {
        request.confirm(selectedIds.toList())
        onFinished()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { PromptTitle(request.title.ifBlank { stringResource(R.string.prompt_title_choose) }) },
        text = {
            Column {
                PromptMessage(request.message)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    request.choices.forEach { choice ->
                        val selected = choice.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !choice.disabled) {
                                    if (request.multiple) {
                                        if (selected) selectedIds.remove(choice.id) else selectedIds.add(choice.id)
                                    } else {
                                        selectedIds.clear()
                                        selectedIds.add(choice.id)
                                    }
                                }
                                .padding(start = (choice.level * 18).dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (request.multiple) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = null,
                                    enabled = !choice.disabled
                                )
                            } else {
                                RadioButton(
                                    selected = selected,
                                    onClick = null,
                                    enabled = !choice.disabled
                                )
                            }
                            Text(
                                text = choice.label.ifBlank { choice.id },
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::confirm, enabled = request.multiple || selectedIds.isNotEmpty()) {
                Text(stringResource(R.string.prompt_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text(stringResource(R.string.prompt_action_cancel))
            }
        }
    )
}

@Composable
private fun ConfirmPromptDialog(
    request: GeckoConfirmPromptRequest,
    onFinished: () -> Unit
) {
    fun dismiss() {
        request.dismiss()
        onFinished()
    }
    fun confirm() {
        request.confirm()
        onFinished()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { PromptTitle(request.title.ifBlank { stringResource(R.string.prompt_title_confirm) }) },
        text = { PromptMessage(request.message) },
        confirmButton = {
            TextButton(onClick = ::confirm) {
                Text(request.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text(request.dismissLabel)
            }
        }
    )
}

@Composable
private fun ColorPromptDialog(
    request: GeckoColorPromptRequest,
    onFinished: () -> Unit
) {
    var value by remember(request) { mutableStateOf(request.defaultValue.ifBlank { "#000000" }) }
    fun dismiss() {
        request.dismiss()
        onFinished()
    }
    fun confirm() {
        request.confirm(value)
        onFinished()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { PromptTitle(request.title.ifBlank { stringResource(R.string.prompt_title_color) }) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_label_color)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                request.predefinedValues.takeIf { it.isNotEmpty() }?.let { values ->
                    Spacer(modifier = Modifier.height(10.dp))
                    values.forEach { color ->
                        Text(
                            text = color,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { value = color }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::confirm) {
                Text(stringResource(R.string.prompt_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text(stringResource(R.string.prompt_action_cancel))
            }
        }
    )
}

@Composable
private fun DateTimePromptDialog(
    request: GeckoDateTimePromptRequest,
    onFinished: () -> Unit
) {
    var value by remember(request) { mutableStateOf(request.defaultValue) }
    fun dismiss() {
        request.dismiss()
        onFinished()
    }
    fun confirm() {
        request.confirm(value)
        onFinished()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { PromptTitle(request.title.ifBlank { stringResource(R.string.prompt_title_date_time) }) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prompt_label_date_time)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                listOfNotNull(
                    request.minValue.takeIf { it.isNotBlank() }?.let { "min: $it" },
                    request.maxValue.takeIf { it.isNotBlank() }?.let { "max: $it" },
                    request.stepValue.takeIf { it.isNotBlank() }?.let { "step: $it" }
                ).takeIf { it.isNotEmpty() }?.let { details ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(details.joinToString("\n"), maxLines = 4)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::confirm) {
                Text(stringResource(R.string.prompt_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text(stringResource(R.string.prompt_action_cancel))
            }
        }
    )
}

@Composable
private fun PromptTitle(text: String) {
    Text(text = text, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun PromptMessage(text: String) {
    if (text.isBlank()) return
    Text(text = text, maxLines = 6, overflow = TextOverflow.Ellipsis)
}
