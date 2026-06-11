package com.dadigua.hyperbrowser.ui.browser

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dadigua.hyperbrowser.browser.BrowserBookmark
import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import kotlinx.coroutines.delay

private val SearchActionBarHeight = 48.dp
private val SearchActionButtonSize = 40.dp
private val SearchActionIconSize = 24.sp

@Composable
internal fun SearchPage(
    initialInput: String,
    history: List<BrowserHistoryEntry>,
    bookmarks: List<BrowserBookmark>,
    onCancel: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onGo: (String) -> Unit
) {
    var query by remember { mutableStateOf(initialInput) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val inputMethodManager = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    val quick = listOf(
        "Google" to "https://google.com",
        "Bilibili" to "https://m.bilibili.com",
        "GitHub" to "https://github.com",
        "AMO Android" to "https://addons.mozilla.org/android/",
        "NeverSSL" to "http://neverssl.com"
    )
    val matches = remember(query, history, bookmarks) {
        val needle = query.trim().lowercase()
        (bookmarks.map { it.title to it.url } + history.map { it.title to it.url })
            .distinctBy { it.second }
            .filter { needle.isBlank() || it.first.lowercase().contains(needle) || it.second.lowercase().contains(needle) }
            .take(12)
    }

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
        activity?.window?.let { window ->
            WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.ime())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.height(SearchActionBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.size(SearchActionButtonSize)) {
                Text("‹", fontSize = SearchActionIconSize, color = Color(0xFF202124))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(SearchActionButtonSize)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFFE7E9F1))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { onGo(query) }),
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF202124)),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .padding(horizontal = 6.dp),
                    decorationBox = { inner ->
                        if (query.isBlank()) {
                            Text("搜索或输入网址", color = Color(0xFF6F737B), style = MaterialTheme.typography.titleMedium)
                        }
                        inner()
                    }
                )
                if (query.isNotBlank()) {
                    Text(
                        "×",
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { query = "" }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 24.sp,
                        color = Color(0xFF5F6368)
                    )
                }
            }
            TextButton(onClick = { onGo(query) }) { Text("Go", fontWeight = FontWeight.Bold) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SearchQuickChip("AI 模式") { onGo("https://google.com/search?q=AI") }
            SearchQuickChip("无痕模式", onClick = onNewPrivateTab)
        }

        Text("快捷访问", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF3C4043))
        quick.forEach { item ->
            SearchSuggestionRow(title = item.first, url = item.second, leading = "↗", chrome = true) { onGo(item.second) }
        }

        Text("书签和历史记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF3C4043))
        if (matches.isEmpty()) {
            Text("没有匹配记录", color = Color(0xFF6F737B))
        } else {
            matches.forEach { item ->
                SearchSuggestionRow(title = item.first, url = item.second, leading = "◷", chrome = true) { onGo(item.second) }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun InputMethodManager.showSoftInputForced(view: View) {
    showSoftInput(view, InputMethodManager.SHOW_FORCED)
}

@Composable
private fun SearchQuickChip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(Color(0xFFE1E4EC))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(if (label.contains("无痕")) "⌂" else "✦", fontSize = 24.sp, color = Color(0xFF202124))
        Text(label, fontWeight = FontWeight.Bold, color = Color(0xFF202124))
    }
}

@Composable
private fun SearchSuggestionRow(
    title: String,
    url: String,
    leading: String,
    chrome: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (chrome) 0.dp else 16.dp))
            .clickable(onClick = onClick)
            .background(if (chrome) Color.Transparent else Color.White)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8EAED)),
            contentAlignment = Alignment.Center
        ) {
            Text(leading, fontSize = 18.sp, color = Color(0xFF5F6368))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF202124))
            Text(url, color = Color(0xFF5F6368), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
    }
}
