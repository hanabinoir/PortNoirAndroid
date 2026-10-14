package com.portnoir.ui

import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Image as ImageAttachmentIcon
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.produceState
import com.portnoir.PortNoirApp
import com.portnoir.data.ChatMessage
import com.portnoir.net.ConnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import android.widget.Toast

private const val MAX_IMAGE_SIZE = 50L * 1024 * 1024

private data class StagedImage(val file: File, val name: String, val mime: String)
private data class ImagePreview(val file: File, val mime: String, val canRemove: Boolean = false)

private suspend fun stageImage(context: android.content.Context, uri: Uri): StagedImage =
    withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: throw IOException("Choose an image")
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }?.takeIf { it.isNotBlank() } ?: "image"
        val directory = File(context.cacheDir, "image-preview").apply { mkdirs() }
        val staged = File(directory, UUID.randomUUID().toString())
        var completed = false
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot read the selected image")
            input.use { source ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_SIZE) throw IOException("Images must be 50 MB or smaller")
                        output.write(buffer, 0, count)
                    }
                }
            }
            completed = true
            StagedImage(staged, File(name).name, mime)
        } finally {
            if (!completed) staged.delete()
        }
    }

private suspend fun copyToDownloads(
    context: android.content.Context,
    message: ChatMessage,
): Uri = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw IOException("Shared Downloads requires Android 10 or newer")
    }
    val sourceFile = File(message.localPath)
    if (!sourceFile.isFile) throw IOException("The local file is no longer available")
    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/PortNoir/"
    val displayName = "${message.id.take(8)}-${File(message.name).name}".take(220)
    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val existing = resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(displayName, relativePath),
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val uri = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
            if (cursor.getLong(1) == sourceFile.length()) uri
            else {
                resolver.delete(uri, null, null)
                null
            }
        } else null
    }
    if (existing != null) return@withContext existing

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, message.mime.ifBlank { "application/octet-stream" })
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val destination = resolver.insert(collection, values)
        ?: throw IOException("Could not create the file in Downloads/PortNoir")
    try {
        val output = resolver.openOutputStream(destination, "w")
            ?: throw IOException("Could not write the file in Downloads/PortNoir")
        sourceFile.inputStream().use { input ->
            output.use { input.copyTo(it) }
        }
        val published = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        if (resolver.update(destination, published, null, null) == 0) {
            throw IOException("Could not publish the file in Downloads/PortNoir")
        }
        destination
    } catch (error: Exception) {
        resolver.delete(destination, null, null)
        throw error
    }
}

private fun downloadsFolderUri(): Uri =
    DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:${Environment.DIRECTORY_DOWNLOADS}/PortNoir",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), content = content) }
}

@Composable
private fun ItemRow(title: String, subtitle: String) = ListItem(
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    headlineContent = { Text(title) },
    supportingContent = { Text(subtitle) },
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ChatHistoryScreen(macId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PortNoirApp
    val context = LocalContext.current
    val name = app.repo.mac(macId)?.name ?: "Mac"
    val states by app.connections.states.collectAsStateWithLifecycle()
    val connected = states[macId] == ConnState.CONNECTED
    val messages by app.chatDatabase.messages().observe(macId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var draft by remember { mutableStateOf("") }
    var stagedImage by remember { mutableStateOf<StagedImage?>(null) }
    var imageSourcesOpen by remember { mutableStateOf(false) }
    var isStagingImage by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImagePreview?>(null) }
    var fileActionsMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val imageScope = androidx.compose.runtime.rememberCoroutineScope()
    val stagedImageOnDispose by rememberUpdatedState(stagedImage)
    DisposableEffect(Unit) {
        onDispose { stagedImageOnDispose?.file?.delete() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        app.connections.reconnect(macId)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            val fileName = cursor?.use {
                val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && it.moveToFirst()) it.getString(column) else null
            } ?: "file"
            app.connections.sendFile(
                macId, uri, fileName,
                context.contentResolver.getType(uri) ?: "application/octet-stream",
            )
        }
    }
    fun stageSelectedImage(uri: Uri, cleanup: File? = null) {
        if (isStagingImage) {
            cleanup?.delete()
            return
        }
        imageScope.launch {
            isStagingImage = true
            try {
                val staged = stageImage(context, uri)
                stagedImage?.file?.delete()
                stagedImage = staged
            } catch (error: IOException) {
                Log.w("PortNoir", "Could not stage image", error)
                Toast.makeText(context, error.message ?: "Could not load image", Toast.LENGTH_LONG).show()
            } catch (error: SecurityException) {
                Log.w("PortNoir", "Image permission was denied", error)
                Toast.makeText(context, "Cannot access the selected image", Toast.LENGTH_LONG).show()
            } finally {
                cleanup?.delete()
                isStagingImage = false
            }
        }
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) stageSelectedImage(uri)
    }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) stageSelectedImage(uri)
    }
    val filesLocationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) {}
    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = cameraUri
        val file = cameraFile
        cameraUri = null
        cameraFile = null
        if (captured && uri != null) stageSelectedImage(uri, file)
        else file?.delete()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name)
                        if (!connected) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text("Offline", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                stagedImage?.let { image ->
                    Box(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                            .size(88.dp),
                    ) {
                        ImageThumbnail(
                            file = image.file,
                            modifier = Modifier.fillMaxSize().clickable {
                                preview = ImagePreview(image.file, image.mime, canRemove = true)
                            },
                        )
                        IconButton(
                            onClick = {
                                image.file.delete()
                                stagedImage = null
                                if (preview?.canRemove == true) preview = null
                            },
                            modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove image", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach file")
                    }
                    IconButton(onClick = { imageSourcesOpen = true }, enabled = !isStagingImage) {
                        Icon(Icons.Outlined.ImageAttachmentIcon, contentDescription = "Attach image")
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 4,
                    )
                    IconButton(onClick = {
                        val image = stagedImage
                        if (image != null) {
                            app.connections.sendImage(macId, image.file, image.name, image.mime)
                            stagedImage = null
                            if (preview?.canRemove == true) preview = null
                        }
                        val body = draft.trim()
                        if (body.isNotEmpty()) {
                            app.connections.sendText(macId, body)
                            draft = ""
                        }
                    }, enabled = stagedImage != null || draft.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(
                    "Messages and files shared with $name will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(
                        message,
                        onImageClick = { file -> preview = ImagePreview(file, message.mime) },
                        onFileClick = { fileActionsMessage = it },
                    )
                }
            }
        }
    }

    if (imageSourcesOpen) {
        AlertDialog(
            onDismissRequest = { imageSourcesOpen = false },
            title = { Text("Choose an image") },
            text = {
                Column {
                    TextButton(onClick = {
                        imageSourcesOpen = false
                        galleryPicker.launch("image/*")
                    }) { Text("Gallery") }
                    TextButton(onClick = {
                        imageSourcesOpen = false
                        filesPicker.launch(arrayOf("image/*"))
                    }) { Text("Files") }
                    TextButton(onClick = {
                        imageSourcesOpen = false
                        val directory = File(context.cacheDir, "image-preview").apply { mkdirs() }
                        val file = File(directory, "camera-${UUID.randomUUID()}.jpg")
                        cameraFile = file
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        cameraUri = uri
                        try {
                            cameraCapture.launch(uri)
                        } catch (error: ActivityNotFoundException) {
                            cameraUri = null
                            cameraFile = null
                            file.delete()
                            Toast.makeText(context, "No camera app is available.", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Camera") }
                }
            },
            confirmButton = {},
        )
    }
    preview?.let { image ->
        ImagePreviewDialog(
            preview = image,
            onDismiss = { preview = null },
            onRemove = {
                image.file.delete()
                if (stagedImage?.file == image.file) stagedImage = null
                preview = null
            },
        )
    }
    fileActionsMessage?.let { message ->
        ModalBottomSheet(onDismissRequest = { fileActionsMessage = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(message.name, style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = {
                        fileActionsMessage = null
                        val uri = if (message.locationUri.isNotEmpty()) {
                            Uri.parse(message.locationUri)
                        } else {
                            FileProvider.getUriForFile(
                                context, "${context.packageName}.files", File(message.localPath),
                            )
                        }
                        val openIntent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, message.mime.ifBlank { "*/*" })
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(Intent.createChooser(openIntent, "Open with"))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open with")
                }
                TextButton(
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                        message.locationUri.isNotEmpty(),
                    onClick = {
                        fileActionsMessage = null
                        imageScope.launch {
                            try {
                                if (message.locationUri.isEmpty()) {
                                    val location = copyToDownloads(context, message)
                                    app.chatDatabase.messages().setLocationUri(message.id, location.toString())
                                }
                                filesLocationPicker.launch(downloadsFolderUri())
                            } catch (error: IOException) {
                                Log.w("PortNoir", "Could not show file in Downloads", error)
                                Toast.makeText(
                                    context,
                                    error.message ?: "Could not show file in Downloads",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } catch (error: SecurityException) {
                                Log.w("PortNoir", "Could not access the shared file location", error)
                                Toast.makeText(
                                    context,
                                    "Could not access Downloads/PortNoir",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } catch (error: ActivityNotFoundException) {
                                Log.w("PortNoir", "No file browser is available", error)
                                Toast.makeText(
                                    context,
                                    "No file browser is available",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "Files · Downloads/PortNoir"
                    } else {
                        "Files · Android 10 or newer"
                    })
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Text(
                        "Shared Downloads access is unavailable on this Android version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ImageThumbnail(
    file: File,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
                val sampleSize = Integer.highestOneBit(maxOf(1, largestDimension / 800))
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize },
                )
            }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Image attachment",
            contentScale = contentScale,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Image", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ImagePreviewDialog(
    preview: ImagePreview,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            ImageThumbnail(
                preview.file,
                Modifier.fillMaxWidth().heightIn(max = 480.dp),
                contentScale = ContentScale.Fit,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.files", preview.file,
                )
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, preview.file.name, uri))
                Toast.makeText(context, "Image copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy image") }
        },
        dismissButton = {
            Row {
                if (preview.canRemove) {
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
                TextButton(onClick = {
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.files", preview.file,
                    )
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, preview.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No app can open this image.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Open image") }
            }
        },
    )
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    onImageClick: (File) -> Unit,
    onFileClick: (ChatMessage) -> Unit,
) {
    val context = LocalContext.current
    val outgoing = message.direction == "outgoing"
    val imageFile = File(message.localPath)
    val imageReady = message.type == "chat.file" && message.mime.startsWith("image/") &&
        imageFile.isFile && (outgoing || message.transferState == "complete")
    val openable = message.type == "chat.file" && message.localPath.isNotEmpty() &&
        (outgoing || message.transferState == "complete")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier.widthIn(max = 320.dp)
                .clickable(enabled = imageReady || openable) {
                    if (imageReady) {
                        onImageClick(imageFile)
                        return@clickable
                    }
                    onFileClick(message)
                }
                .background(
                    if (outgoing) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (message.type == "chat.file") {
                if (imageReady) {
                    ImageThumbnail(imageFile, Modifier.sizeIn(maxWidth = 280.dp, maxHeight = 280.dp))
                } else {
                    Text(message.name, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "${message.size} bytes · ${message.transferState.ifEmpty { if (message.delivered) "sent" else "sending" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(message.body)
                if (outgoing && !message.delivered) {
                    if (message.transferState == "pending") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Pending",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    (context.applicationContext as PortNoirApp)
                                        .connections.recall(message.macId, message.id)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Recall")
                            }
                        }
                    } else {
                        Text("Sending", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDetailScreen(key: String, onBack: () -> Unit) {
    val item = SettingsItem.entries.firstOrNull { it.key == key }
    DetailScaffold(item?.title ?: "Settings", onBack) {
        when (item) {
            SettingsItem.FEATURES -> {
                ItemRow("Port", "Chat and file sharing. Needs no extra permission.")
                ItemRow("Pictures", "Photo access is requested when this feature ships.")
                ItemRow("SMS", "Optional. Needs SMS permissions.")
                ItemRow("Calls", "Optional. Needs phone permissions. Call audio stays on the phone.")
                ItemRow("Camera", "Used only to scan the pairing code.")
                ItemRow("Notifications", "Shows that PortNoir is keeping your Mac connected.")
            }
            SettingsItem.ADVANCED -> {
                ItemRow("Battery optimisation", "Exempting PortNoir keeps the connection alive in the background. Coming soon.")
                ItemRow("Connection", "Searches for paired PCs once when the app resumes. Offline PCs are not retried until the next resume.")
            }
            SettingsItem.ABOUT -> {
                val ctx = LocalContext.current
                val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
                ItemRow("PortNoir", "Version $version")
                ItemRow("Protocol", "Version 1")
            }
            SettingsItem.LICENCES -> {
                ItemRow("AndroidX and Jetpack Compose", "Apache License 2.0")
                ItemRow("OkHttp", "Apache License 2.0")
                ItemRow("ML Kit Barcode Scanning", "Google ML Kit terms")
                ItemRow("Material Icons", "Apache License 2.0")
            }
            else -> {}
        }
    }
}
