package com.tvloja.signage.domain.usecase

import com.tvloja.signage.data.local.files.MediaFileStore
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.domain.model.NewMedia
import com.tvloja.signage.domain.repository.PlaylistRepository
import com.tvloja.signage.domain.repository.SettingsRepository
import com.tvloja.signage.util.AppLogger
import com.tvloja.signage.work.WorkScheduler
import java.util.Locale

/** Cadastra uma mídia por URL (http/https). Opcionalmente agenda o download para uso offline. */
class AddRemoteMediaUseCase(
    private val repository: PlaylistRepository,
    private val settings: SettingsRepository,
    private val scheduler: WorkScheduler,
) {
    suspend operator fun invoke(url: String, name: String?, downloadForOffline: Boolean): ImportResult {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return ImportResult.Failure("A URL deve começar com http:// ou https://")
        }
        val fileName = clean.substringBefore('?').substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val type = when (ext) {
            "mp4", "m4v", "mkv", "webm", "mov", "ts", "3gp" -> MediaType.VIDEO
            "jpg", "jpeg", "png", "webp" -> MediaType.IMAGE
            else -> return ImportResult.Failure("Extensão não reconhecida. Use .jpg, .jpeg, .png, .webp ou .mp4")
        }
        val displayName = name?.trim().takeUnless { it.isNullOrBlank() } ?: fileName.substringBeforeLast('.')
        val id = repository.add(
            NewMedia(
                name = displayName,
                type = type,
                uri = clean,
                sourceUri = clean,
                durationMs = settings.current().defaultImageDurationSec * 1000L,
            )
        )
        if (downloadForOffline) scheduler.enqueueDownload(id)
        AppLogger.i("Mídia remota adicionada: $displayName (download offline: $downloadForOffline)")
        return ImportResult.Success(id, displayName)
    }
}

/** Remove a mídia da playlist, cancela downloads pendentes e apaga o arquivo se pertencer ao app. */
class DeleteMediaUseCase(
    private val repository: PlaylistRepository,
    private val fileStore: MediaFileStore,
    private val scheduler: WorkScheduler,
) {
    suspend operator fun invoke(id: Long) {
        scheduler.cancelDownload(id)
        val removed = repository.delete(id) ?: return
        fileStore.deleteIfManaged(removed.uri)
        AppLogger.i("Mídia removida: ${removed.name}")
    }
}
