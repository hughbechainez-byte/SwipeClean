package com.swipeclean

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var treeUri by mutableStateOf<Uri?>(null)
    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            treeUri = it
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7C4DFF),
                    secondary = Color(0xFF03DAC6),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SwipeCleanApp(
                        treeUri = treeUri,
                        onPickFolder = { openTreeLauncher.launch(null) }
                    )
                }
            }
        }
    }
}

data class FileItem(
    val document: DocumentFile,
    val name: String,
    val size: Long,
    val mime: String?,
    val lastModified: Long,
    val isImage: Boolean,
    val isVideo: Boolean
)

enum class Screen { PICK, SWIPE, TRASH }

@Composable
fun SwipeCleanApp(treeUri: Uri?, onPickFolder: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.PICK) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }
    var trash by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Load files when tree is selected
    LaunchedEffect(treeUri) {
        if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
            if (root != null && root.isDirectory) {
                val list = root.listFiles()
                    .filter { it.isFile }
                    .map { doc ->
                        val mime = doc.type
                        FileItem(
                            document = doc,
                            name = doc.name ?: "unknown",
                            size = doc.length(),
                            mime = mime,
                            lastModified = doc.lastModified(),
                            isImage = mime?.startsWith("image/") == true,
                            isVideo = mime?.startsWith("video/") == true
                        )
                    }
                    .sortedByDescending { it.lastModified }
                files = list
                currentIndex = 0
                screen = if (list.isNotEmpty()) Screen.SWIPE else Screen.PICK
            }
        }
    }

    when (screen) {
        Screen.PICK -> PickFolderScreen(onPickFolder = onPickFolder, hasFiles = files.isNotEmpty(), onStart = { screen = Screen.SWIPE })
        Screen.SWIPE -> {
            if (files.isEmpty() || currentIndex >= files.size) {
                FinishedScreen(
                    trashCount = trash.size,
                    onReviewTrash = { screen = Screen.TRASH },
                    onPickAgain = {
                        files = emptyList()
                        trash = emptyList()
                        screen = Screen.PICK
                    }
                )
            } else {
                SwipeScreen(
                    item = files[currentIndex],
                    remaining = files.size - currentIndex,
                    total = files.size,
                    trashCount = trash.size,
                    onKeep = {
                        currentIndex++
                    },
                    onDelete = {
                        trash = trash + files[currentIndex]
                        currentIndex++
                    },
                    onOpenTrash = { screen = Screen.TRASH }
                )
            }
        }
        Screen.TRASH -> TrashReviewScreen(
            trash = trash,
            onRestore = { item ->
                trash = trash.filter { it != item }
            },
            onEmptyTrash = {
                // Permanently delete
                trash.forEach { it.document.delete() }
                trash = emptyList()
                // Also remove from remaining files if any
                files = files.filter { f -> trash.none { it.document.uri == f.document.uri } }
                screen = Screen.SWIPE
            },
            onBack = { screen = Screen.SWIPE }
        )
    }
}

@Composable
fun PickFolderScreen(onPickFolder: () -> Unit, hasFiles: Boolean, onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Swipe Clean", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tinder-style cleaner for your Downloads folder.\nSwipe left to trash, right to keep.",
            textAlign = TextAlign.Center,
            color = Color.Gray
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onPickFolder,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Select Downloads (or any folder)")
        }
        if (hasFiles) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with previous folder")
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Tip: On the system picker, navigate to Downloads and tap \"Use this folder\".",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SwipeScreen(
    item: FileItem,
    remaining: Int,
    total: Int,
    trashCount: Int,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onOpenTrash: () -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = 300f

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar with counter
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${total - remaining + 1} / $total",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text("$remaining left", color = Color.Gray)
            BadgedBox(badge = {
                if (trashCount > 0) Badge { Text("$trashCount") }
            }) {
                IconButton(onClick = onOpenTrash) {
                    Icon(Icons.Default.Delete, contentDescription = "Trash")
                }
            }
        }

        // Card area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .graphicsLayer {
                        translationX = offsetX.value
                        rotationZ = offsetX.value / 40f
                        alpha = 1f - (kotlin.math.abs(offsetX.value) / 1000f).coerceIn(0f, 0.4f)
                    }
                    .pointerInput(item) {
                        detectDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    when {
                                        offsetX.value > threshold -> {
                                            offsetX.animateTo(1000f, tween(200))
                                            onKeep()
                                            offsetX.snapTo(0f)
                                        }
                                        offsetX.value < -threshold -> {
                                            offsetX.animateTo(-1000f, tween(200))
                                            onDelete()
                                            offsetX.snapTo(0f)
                                        }
                                        else -> offsetX.animateTo(0f, tween(200))
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    offsetX.snapTo(offsetX.value + dragAmount.x)
                                }
                            }
                        )
                    },
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Preview
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF2A2A2A)),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            item.isImage || item.isVideo -> {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(item.document.uri)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = item.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            else -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = when {
                                            item.mime?.contains("pdf") == true -> Icons.Default.PictureAsPdf
                                            item.mime?.contains("zip") == true || item.name.endsWith(".apk") -> Icons.Default.Archive
                                            item.mime?.contains("audio") == true -> Icons.Default.AudioFile
                                            else -> Icons.Default.InsertDriveFile
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(96.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(item.mime ?: "File", color = Color.Gray)
                                }
                            }
                        }
                    }

                    // Info
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            item.name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatSize(item.size)}  •  ${formatDate(item.lastModified)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            // Swipe hints
            if (offsetX.value > 50) {
                Text("KEEP", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 28.sp,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp))
            }
            if (offsetX.value < -50) {
                Text("TRASH", color = Color(0xFFF44336), fontWeight = FontWeight.Bold, fontSize = 28.sp,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp))
            }
        }

        // Buttons as fallback / accessibility
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(
                onClick = onDelete,
                containerColor = Color(0xFFF44336),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Close, contentDescription = "Trash")
            }
            FloatingActionButton(
                onClick = onKeep,
                containerColor = Color(0xFF4CAF50),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Favorite, contentDescription = "Keep")
            }
        }
    }
}

@Composable
fun FinishedScreen(trashCount: Int, onReviewTrash: () -> Unit, onPickAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(72.dp), tint = Color(0xFF4CAF50))
        Spacer(Modifier.height(16.dp))
        Text("All done!", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("$trashCount items in trash", color = Color.Gray)
        Spacer(Modifier.height(32.dp))
        if (trashCount > 0) {
            Button(onClick = onReviewTrash, modifier = Modifier.fillMaxWidth()) {
                Text("Review Trash & Delete")
            }
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(onClick = onPickAgain, modifier = Modifier.fillMaxWidth()) {
            Text("Pick another folder")
        }
    }
}

@Composable
fun TrashReviewScreen(
    trash: List<FileItem>,
    onRestore: (FileItem) -> Unit,
    onEmptyTrash: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Trash (${trash.size})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }

        if (trash.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Trash is empty", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                items(trash) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                when {
                                    item.isImage -> Icons.Default.Image
                                    item.isVideo -> Icons.Default.VideoFile
                                    else -> Icons.Default.InsertDriveFile
                                },
                                contentDescription = null,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            TextButton(onClick = { onRestore(item) }) {
                                Text("Restore")
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onEmptyTrash,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Permanently Delete All (${trash.size})")
            }
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

fun formatDate(millis: Long): String {
    if (millis <= 0) return ""
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(millis))
}
