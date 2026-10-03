package com.tvloja.signage.domain.usecase

import android.net.Uri
import com.tvloja.signage.data.local.files.LocalMediaFile
import com.tvloja.signage.data.local.files.MediaFileStore
import com.tvloja.signage.data.local.files.MediaInspector
import com.tvloja.signage.data.local.files.StorageFullException
import com.tvloja.signage.domain.model.MediaType
import com.tvloja.signage.domain.model.NewMedia
import com.tvloja.signage.domain.repository.PlaylistRepository
import com.tvloja.signage.domain.repository.SettingsRepository
import com.tvloja.signage.util.AppLogger
import kotlinx.coroutines.CancellationException

sealed interface ImportResult {
    data class Success(val id: Long, val name: String) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/**
 * Valida (detecta arquivo corrompido), opcionalmente copia para o armazenamento do app e cadastra a mídia.
 */
class ImportLocalMediaUseCase(
    private val fileStore: MediaFileStore,
    private val inspector: MediaInspector,
    private val repository: PlaylistRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(
        file: LocalMediaFile,
        copyToAppStorage: Boolean,
        onProgress: (Float) -> Unit,
    ): ImportResult {
        return try {
            val info = inspector.inspect(file.uri, file.type)
                ?: return ImportResult.Failure(
                    if (file.type == MediaType.IMAGE) "Imagem inválida ou corrompida: ${file.name}"
                    else "Vídeo inválido, corrompido ou em formato não suportado: ${file.name}"
                )

            val playbackUri = if (copyToAppStorage) {
                val dest = fileStore.importFromUri(file.uri, file.name, file.sizeBytes, onProgress)
                Uri.fromFile(dest).toString()
            } else file.uri.toString()

            val name = file.name.substringBeforeLast('.').ifBlank { file.name }
            val id = repository.add(
                NewMedia(
                    name = name,
                    type = file.type,
                    uri = playbackUri,
                    sourceUri = file.uri.toString(),
                    durationMs = settings.current().defaultImageDurationSec * 1000L,
                    width = info.width,
                    height = info.height,
                    sizeBytes = file.sizeBytes.takeIf { it > 0 },
                    mimeType = info.mimeType,
                    mediaDurationMs = info.durationMs,
                )
            )
            AppLogger.i("Mídia adicionada: $name (${file.type})")
            ImportResult.Success(id, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageFullException) {
            AppLogger.e("Armazenamento cheio ao importar ${file.name}", e)
            ImportResult.Failure(e.message ?: "Armazenamento cheio")
        } catch (e: Exception) {
            AppLogger.e("Falha ao importar ${file.name}", e)
            ImportResult.Failure("Falha ao importar ${file.name}: ${e.message}")
        }
    }
}
