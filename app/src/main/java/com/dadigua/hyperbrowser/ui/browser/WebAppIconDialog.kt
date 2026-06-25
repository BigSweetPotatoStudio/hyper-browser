package com.dadigua.hyperbrowser.ui.browser

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dadigua.hyperbrowser.R
import com.dadigua.hyperbrowser.webapp.WebAppIconPreset
import com.dadigua.hyperbrowser.webapp.WebAppIconPresets
import java.text.BreakIterator

internal data class WebAppDetailsDialogState(
    val webAppId: String? = null,
    val name: String,
    val startUrl: String,
    val siteIconPath: String?,
    val selectedIcon: WebAppIconSelection = WebAppIconSelection.Site
)

internal sealed class WebAppIconSelection {
    data object Site : WebAppIconSelection()
    data class Preset(val id: String) : WebAppIconSelection()
    data class Image(val iconPath: String) : WebAppIconSelection()
}

@Composable
internal fun WebAppDetailsDialog(
    state: WebAppDetailsDialogState,
    onNameChange: (String) -> Unit,
    onStartUrlChange: (String) -> Unit,
    onSelect: (WebAppIconSelection) -> Unit,
    onChooseImage: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.webapp_install_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    WebAppIconPreview(
                        selection = state.selectedIcon,
                        siteIconPath = state.siteIconPath,
                        name = state.name,
                        startUrl = state.startUrl,
                        modifier = Modifier.size(72.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        val fallbackTitle = stringResource(R.string.browser_search_source_webapp)
                        val displayTitle = state.name.ifBlank {
                            hostLabel(state.startUrl).ifBlank { fallbackTitle }
                        }
                        Text(
                            displayTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            state.startUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5F6368),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            selectedIconLabel(state.selectedIcon, state.siteIconPath != null),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF126D6A),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.webapp_field_title)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = state.startUrl,
                    onValueChange = onStartUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.webapp_field_address)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    )
                )

                HorizontalDivider()

                Text(
                    stringResource(R.string.webapp_icon_source_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WebAppIconOption(
                        selected = state.selectedIcon == WebAppIconSelection.Site,
                        label = stringResource(
                            if (state.siteIconPath != null) {
                                R.string.webapp_icon_site
                            } else {
                                R.string.webapp_icon_title_fallback
                            }
                        ),
                        onClick = { onSelect(WebAppIconSelection.Site) }
                    ) {
                        WebAppIconPreview(
                            selection = WebAppIconSelection.Site,
                            siteIconPath = state.siteIconPath,
                            name = state.name,
                            startUrl = state.startUrl,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    WebAppIconPresets.all.forEach { preset ->
                        WebAppIconOption(
                            selected = state.selectedIcon == WebAppIconSelection.Preset(preset.id),
                            label = stringResource(preset.labelRes),
                            onClick = { onSelect(WebAppIconSelection.Preset(preset.id)) }
                        ) {
                            PresetIcon(preset = preset, modifier = Modifier.size(48.dp))
                        }
                    }
                }

                TextButton(onClick = onChooseImage) {
                    Text(stringResource(R.string.webapp_icon_choose_image))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_action_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_action_cancel))
            }
        }
    )
}

@Composable
private fun selectedIconLabel(selection: WebAppIconSelection, hasSiteIcon: Boolean): String =
    when (selection) {
        WebAppIconSelection.Site -> stringResource(
            if (hasSiteIcon) {
                R.string.webapp_icon_site
            } else {
                R.string.webapp_icon_title_fallback
            }
        )
        is WebAppIconSelection.Image -> stringResource(R.string.webapp_icon_selected_image)
        is WebAppIconSelection.Preset -> WebAppIconPresets.find(selection.id)
            ?.let { stringResource(it.labelRes) }
            ?: stringResource(R.string.webapp_icon_default_library)
    }

@Composable
private fun WebAppIconOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val borderColor = if (selected) Color(0xFF126D6A) else Color(0xFFDADCE0)
    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        icon()
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF3C4043),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WebAppIconPreview(
    selection: WebAppIconSelection,
    siteIconPath: String?,
    name: String,
    startUrl: String,
    modifier: Modifier = Modifier
) {
    when (selection) {
        WebAppIconSelection.Site -> FileIconOrFallback(siteIconPath, name, startUrl, modifier)
        is WebAppIconSelection.Image -> FileIconOrFallback(selection.iconPath, name, startUrl, modifier)
        is WebAppIconSelection.Preset -> {
            val preset = WebAppIconPresets.find(selection.id)
            if (preset != null) {
                PresetIcon(preset = preset, modifier = modifier)
            } else {
                FallbackIcon(name = name, startUrl = startUrl, modifier = modifier)
            }
        }
    }
}

@Composable
private fun FileIconOrFallback(
    path: String?,
    name: String,
    startUrl: String,
    modifier: Modifier
) {
    val bitmap = remember(path) {
        path?.let { BitmapFactory.decodeFile(it) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFE8EAED))
        )
    } else {
        FallbackIcon(name = name, startUrl = startUrl, modifier = modifier)
    }
}

@Composable
private fun PresetIcon(preset: WebAppIconPreset, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(preset.backgroundColor)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(preset.drawableRes),
            contentDescription = null,
            tint = Color(preset.foregroundColor),
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun FallbackIcon(name: String, startUrl: String, modifier: Modifier = Modifier) {
    val label = remember(name, startUrl) {
        val source = name.ifBlank { hostLabel(startUrl) }.ifBlank { "W" }
        firstDisplaySymbol(source).ifBlank { "W" }
    }
    val color = remember(startUrl) { fallbackIconColor(startUrl) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black
        )
    }
}

private fun hostLabel(url: String): String =
    runCatching { java.net.URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

private fun firstDisplaySymbol(value: String): String {
    val trimmed = value.trimStart()
    if (trimmed.isBlank()) return ""
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(trimmed)
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        val symbol = trimmed.substring(start, end).trim()
        if (symbol.isNotEmpty()) return symbol.uppercase()
        start = end
        end = iterator.next()
    }
    return ""
}

private fun fallbackIconColor(value: String): Color {
    val colors = listOf(
        Color(0xFF1967D2),
        Color(0xFF0F9D58),
        Color(0xFFD93025),
        Color(0xFF00897B),
        Color(0xFF7B1FA2),
        Color(0xFFF57C00)
    )
    return colors[Math.floorMod(value.hashCode(), colors.size)]
}
