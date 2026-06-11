package com.dadigua.hyperbrowser.ui.browser

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoSession

@Composable
internal fun BrowserFindBar(
    query: String,
    result: GeckoSession.FinderResult?,
    onQueryChange: (String) -> Unit,
    onFindNext: () -> Unit,
    onFindPrevious: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val inputMethodManager = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    var requestedInitialFocus by remember { mutableStateOf(false) }

    DisposableEffect(activity) {
        val window = activity?.window
        val previousSoftInputMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        onDispose {
            if (previousSoftInputMode != null) {
                window.setSoftInputMode(previousSoftInputMode)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!requestedInitialFocus) {
            requestedInitialFocus = true
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
            inputMethodManager.restartInput(view)
            keyboardController?.show()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            activity?.window?.let { window ->
                WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.ime())
            }
            delay(120)
            runCatching { focusRequester.requestFocus() }
            inputMethodManager.restartInput(view)
            keyboardController?.show()
            inputMethodManager.showSoftInputForced(view)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(Color(0xFFF7F8FC))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Find in page") },
            trailingIcon = {
                Text(
                    text = findResultLabel(query, result),
                    color = Color(0xFF5F6368),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(onSearch = { onFindNext() }),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
        )
        IconButton(
            onClick = onFindPrevious,
            enabled = query.isNotBlank(),
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Previous match")
        }
        IconButton(
            onClick = onFindNext,
            enabled = query.isNotBlank(),
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Next match")
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFFE8EAED), CircleShape)
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Close find in page")
        }
    }
}

@Suppress("DEPRECATION")
private fun InputMethodManager.showSoftInputForced(view: View) {
    showSoftInput(view, InputMethodManager.SHOW_FORCED)
}

private fun findResultLabel(query: String, result: GeckoSession.FinderResult?): String {
    if (query.isBlank() || result == null) return ""
    if (!result.found || result.total == 0) return "0/0"
    return "${result.current}/${result.total}"
}
