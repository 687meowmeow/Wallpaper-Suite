package com.wepwawet.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Input
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.Activity
import android.content.Intent
import android.net.Uri

/**
 * v0.8.18: the "+ folder" MENU — "make the create folder button come up
 * with menu asking if you want to use an already existing or make one".
 * One place, three call sites: the "＋" chip's Add-image-folder option,
 * Discover's Save-to-folder dialog, and the top-bar "+" when no folder is
 * active (which also offers the general import via [onImportOther]).
 * v0.8.21: no ellipses in the option labels ("the menu that appears has
 * ... at the end of every option"), and the group option moved OUT to the
 * "＋" chip's own dropdown in LibraryScreen.
 */
@Composable
fun FolderAddMenuDialog(
    onDismiss: () -> Unit,
    onChooseExisting: () -> Unit,
    onCreateNew: () -> Unit,
    onImportOther: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
        title = { Text("Add image folder") },
        text = {
            Column {
                OutlinedButton(
                    onClick = onChooseExisting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Use an existing folder")
                }
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedButton(
                    onClick = onCreateNew,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Create a new folder")
                }
                if (onImportOther != null) {
                    Spacer(Modifier.padding(top = 8.dp))
                    TextButton(
                        onClick = onImportOther,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Input, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import other files")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * v0.8.18: the self-contained CREATE-FOLDER flow (previously private to
 * the Discover screen): system parent picker → folder name + optional
 * hidden-from-gallery (.nomedia) → subfolder created under the parent
 * grant and registered as a library folder. Reused by Discover's save
 * dialog and the Wallpapers page.
 */
@Composable
fun CreateFolderFlow(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCreated: (dirUri: String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var parent by remember { mutableStateOf<String?>(null) }
    var showName by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    val parentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            ImageFolderAssets.takePersistableGrants(context, uri)
            parent = uri.toString()
            showName = true
        } else {
            onDismiss()
        }
    }

    // Entering the flow launches the parent picker once.
    LaunchedEffect(visible) {
        if (visible && parent == null && !showName) {
            try {
                parentPicker.launch(null)
            } catch (t: Throwable) {
                WepWawetLogger.appWarn("CreateFolderFlow: parent picker failed",
                    "exception" to t.javaClass.name)
                onDismiss()
            }
        }
    }

    if (showName) {
        AlertDialog(
            onDismissRequest = {
                if (!creating) { showName = false; parent = null; onDismiss() }
            },
            title = { Text("Create folder") },
            text = {
                Column {
                    Text(
                        "The new folder is created inside the location you " +
                            "picked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(top = 12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Folder name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.padding(top = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = hidden,
                            onCheckedChange = { hidden = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Hidden from gallery")
                    }
                    Text(
                        "Hiding drops a .nomedia marker inside — gallery apps " +
                            "and MediaStore skip folders that have one.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (creating) return@TextButton
                        creating = true
                        val parentTree = parent
                        scope.launch {
                            try {
                                val dirUri = withContext(Dispatchers.IO) {
                                    if (parentTree == null) null
                                    else ImageFolderAssets.createSubfolder(
                                        context, parentTree, name, hidden)
                                }
                                if (dirUri == null) {
                                    WepWawetLogger.appWarn("CreateFolderFlow: create failed",
                                        "name" to name.take(60))
                                    onDismiss()
                                } else {
                                    ImageFolderAssets.addFolder(context, dirUri)
                                    WepWawetLogger.appInfo("CreateFolderFlow: created",
                                        "name" to name.trim().take(60),
                                        "hidden" to hidden)
                                    showName = false
                                    parent = null
                                    onCreated(dirUri)
                                }
                            } finally {
                                creating = false
                                name = ""
                            }
                        }
                    },
                    enabled = name.isNotBlank() && !creating,
                ) { Text(if (creating) "Creating…" else "Create") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showName = false; parent = null; onDismiss() },
                    enabled = !creating,
                ) { Text("Cancel") }
            },
        )
    }
}

/**
 * v0.8.18: the ANDROID WAY to import — ACTION_GET_CONTENT wrapped in the
 * system chooser, which pops the "use Files or Gallery" disambiguation
 * menu the user asked for ("make it pop up with that one android menu
 * that asks if you want to use files or gallery when you try to import").
 * EXTRA_ALLOW_MULTIPLE keeps multi-select working in both apps.
 */
fun getContentChooserIntent(mimeTypes: Array<String>, allowMultiple: Boolean): Intent {
    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
        type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
        if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addCategory(Intent.CATEGORY_OPENABLE)
    }
    return Intent.createChooser(intent, null)
}

/** Collect every uri a GET_CONTENT result carries (clipData + data). */
fun urisFromGetContentResult(resultCode: Int, data: Intent?): List<Uri> {
    if (resultCode != Activity.RESULT_OK) return emptyList()
    val intent = data ?: return emptyList()
    val out = mutableListOf<Uri>()
    intent.clipData?.let { cd ->
        for (i in 0 until cd.itemCount) out += cd.getItemAt(i).uri
    }
    if (out.isEmpty()) intent.data?.let { out += it }
    return out
}
