package com.dadigua.hyperbrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.dadigua.hyperbrowser.R

internal data class FindInPageUiState(
    val query: String = "",
    val found: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val searching: Boolean = false
)

internal fun findInPageResultLabel(
    state: FindInPageUiState,
    noMatchesLabel: String
): String = when {
    state.query.isBlank() -> ""
    state.searching -> "…"
    !state.found -> noMatchesLabel
    state.total > 0 -> "${state.current.coerceAtLeast(1)}/${state.total}"
    state.current > 0 -> state.current.toString()
    else -> noMatchesLabel
}

@Composable
internal fun FindInPageBar(
    state: FindInPageUiState,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val noMatchesLabel = stringResource(R.string.find_in_page_no_matches)
    val canNavigate = state.query.isNotBlank() && state.found

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.find_in_page_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (canNavigate) onNext() })
        )
        Text(
            text = findInPageResultLabel(state, noMatchesLabel),
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .widthIn(min = 44.dp),
            style = MaterialTheme.typography.labelMedium
        )
        IconButton(onClick = onPrevious, enabled = canNavigate) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowUp,
                contentDescription = stringResource(R.string.find_in_page_previous)
            )
        }
        IconButton(onClick = onNext, enabled = canNavigate) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = stringResource(R.string.find_in_page_next)
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.find_in_page_close)
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
