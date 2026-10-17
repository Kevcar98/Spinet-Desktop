package dev.spinet.download

expect suspend fun readDownloadLedgerFile(subfolder: String): String?

expect suspend fun writeDownloadLedgerFile(subfolder: String, json: String): Boolean

expect suspend fun readUploadLedgerFile(): String?

expect suspend fun writeUploadLedgerFile(json: String): Boolean

expect fun ledgerLocationLabel(): String
