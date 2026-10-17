package dev.spinet.update

actual fun createUpdateInstaller(): UpdateInstaller? =
    if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
        DesktopUpdateInstallerAdapter()
    } else {
        null
    }
