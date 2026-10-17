package dev.spinet.ui

import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal object DesktopFilePickers {
    fun chooseSave(suggestedName: String, ext: String): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Save"
            selectedFile = File(suggestedName)
            fileFilter = FileNameExtensionFilter("*.$ext", ext)
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        var file = chooser.selectedFile ?: return null
        if (!file.name.lowercase().endsWith(".$ext")) {
            file = File(file.parentFile, "${file.name}.$ext")
        }
        return file
    }

    fun chooseOpen(ext: String?): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Open"
            if (ext != null) fileFilter = FileNameExtensionFilter("*.$ext", ext)
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.takeIf { it.isFile }
    }

    fun chooseOpenMany(ext: String?): List<File> {
        val chooser = JFileChooser().apply {
            dialogTitle = "Open"
            isMultiSelectionEnabled = true
            if (ext != null) fileFilter = FileNameExtensionFilter("*.$ext", ext)
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
        return chooser.selectedFiles.filter { it.isFile }
    }

    fun chooseDirectory(): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Choose a folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.takeIf { it.isDirectory }
    }
}
