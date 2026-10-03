package com.tvloja.signage.domain.model

enum class MediaType { IMAGE, VIDEO }

/**
 * Item da playlist (modelo de domínio, independente de Room/rede).
 *
 * @param uri         o que é efetivamente reproduzido: file://, content:// ou http(s)://
 * @param sourceUri   origem do item (arquivo original no USB, URL remota). Quando uma URL é baixada
 *                    para o aparelho, [uri] passa a apontar para o arquivo local e [sourceUri] guarda a URL.
 * @param durationMs  tempo de exibição (apenas imagens). Vídeos duram o tempo do próprio vídeo.
 * @param remoteId    identificador do item no servidor central (null = item cadastrado localmente).
 */
data class MediaItem(
    val id: Long,
    val name: String,
    val type: MediaType,
    val uri: String,
    val sourceUri: String?,
    val durationMs: Long,
    val position: Int,
    val enabled: Boolean,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val mediaDurationMs: Long?,
    val remoteId: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isStreaming: Boolean get() = uri.startsWith("http://") || uri.startsWith("https://")
}

/** Dados para cadastrar uma nova mídia. */
data class NewMedia(
    val name: String,
    val type: MediaType,
    val uri: String,
    val sourceUri: String?,
    val durationMs: Long,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val mediaDurationMs: Long? = null,
    val remoteId: String? = null,
)

/** Item como descrito pelo servidor (manifesto remoto). */
data class RemoteMediaSpec(
    val remoteId: String,
    val name: String,
    val type: MediaType,
    val url: String,
    val durationMs: Long,
    val enabled: Boolean,
)

/** Resultado de uma sincronização com o servidor. */
data class SyncOutcome(
    val added: Int,
    val updated: Int,
    val removed: List<MediaItem>,
    /** URIs de arquivos locais que deixaram de ser usados (item removido ou URL trocada). */
    val obsoleteUris: List<String>,
    /** Itens que ainda apontam para uma URL e devem ser baixados para uso offline. */
    val needsDownload: List<Long>,
)
