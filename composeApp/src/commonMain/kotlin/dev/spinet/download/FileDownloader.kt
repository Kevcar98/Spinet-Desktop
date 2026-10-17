package dev.spinet.download

sealed interface DownloadResult {
    data class Success(val location: String) : DownloadResult
    data class Error(val message: String) : DownloadResult
}

interface FileDownloader {
    suspend fun downloadAudio(
        url: String,
        title: String,
        artist: String = "",
        artworkUrl: String = "",

        subfolder: String = "",
        onProgress: (Float) -> Unit = {}
    ): DownloadResult
}
