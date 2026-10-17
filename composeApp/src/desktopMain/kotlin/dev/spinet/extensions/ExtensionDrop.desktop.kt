package dev.spinet.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalComposeUiApi::class)
private fun filesIn(event: DragAndDropEvent): List<File>? = runCatching {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
}.getOrNull()

private fun List<File>.jars() = filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun ExtensionDropTarget(
    message: String?,
    onDropped: (files: List<PickedExtensionFile>) -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dropped by rememberUpdatedState(onDropped)

    var dragging by remember { mutableStateOf<Int?>(null) }

    var showMessages by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }

    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                dragging = filesIn(event)?.jars()?.size ?: -1
            }

            override fun onExited(event: DragAndDropEvent) {
                dragging = null
            }

            override fun onEnded(event: DragAndDropEvent) {
                dragging = null
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = null
                val files = filesIn(event) ?: return false
                val jars = files.jars()
                if (jars.isEmpty()) {
                    refused = if (files.size == 1) "${files[0].name} isn't an extension. Extensions are .jar files."
                        else "None of those are extensions. Extensions are .jar files."
                    return false
                }
                refused = if (jars.size < files.size) {
                    "Skipped ${files.size - jars.size} file(s) that aren't .jar extensions."
                } else null
                showMessages = true
                scope.launch {
                    val picked = withContext(Dispatchers.IO) {
                        jars.map { PickedExtensionFile(it.name, it.readBytes()) }
                    }
                    dropped(picked)
                }
                return true
            }
        }
    }

    LaunchedEffect(message, showMessages) {
        if (showMessages) {
            delay(MESSAGE_SHOWN_MS)
            showMessages = false
        }
    }
    LaunchedEffect(refused) {
        if (refused != null) {
            delay(MESSAGE_SHOWN_MS)
            refused = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> filesIn(event) != null },
                target = target
            )
    ) {
        content()

        dragging?.let { count ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                    .padding(24.dp)
                    .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Column(Modifier.padding(horizontal = 32.dp, vertical = 24.dp)) {
                        Text(
                            when (count) {
                                -1 -> "Drop to install extensions"
                                0 -> "No extension files here"
                                1 -> "Drop to install 1 extension"
                                else -> "Drop to install $count extensions"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            if (count == 0) "Extensions are .jar files." else "They arrive switched off, under Settings > Sources.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        val note = refused ?: message?.takeIf { showMessages }
        if (note != null && dragging == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .widthIn(max = 560.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                tonalElevation = 6.dp
            ) {
                Text(note, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private const val MESSAGE_SHOWN_MS = 5_000L
