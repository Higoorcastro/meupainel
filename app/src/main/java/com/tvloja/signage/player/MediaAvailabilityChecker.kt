package com.tvloja.signage.player

import android.content.Context
import android.net.Uri
import com.tvloja.signage.domain.model.MediaItem
import java.io.File

/**
 * Verificação rápida, antes de exibir, se a mídia existe (arquivo apagado, pendrive removido etc.).
 * Evita mostrar uma tela preta com fade para um item que certamente vai falhar.
 */
class MediaAvailabilityChecker(private val context: Context) {

    /** @return null se disponível, ou o motivo da indisponibilidade. */
    fun check(item: MediaItem): String? {
        val uri = Uri.parse(item.uri)
        return when (uri.scheme) {
            "file" -> {
                val file = uri.path?.let { File(it) }
                when {
                    file == null -> "Caminho inválido"
                    !file.exists() -> "Arquivo não encontrado: ${file.name}"
                    file.length() == 0L -> "Arquivo vazio: ${file.name}"
                    !file.canRead() -> "Sem permissão de leitura: ${file.name}"
                    else -> null
                }
            }
            "content" -> runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { } ?: return "Conteúdo indisponível"
                null
            }.getOrElse { "Conteúdo inacessível (${it.javaClass.simpleName}). O pendrive foi removido?" }
            "http", "https" -> null
            else -> "Tipo de URI não suportado: ${uri.scheme}"
        }
    }
}
