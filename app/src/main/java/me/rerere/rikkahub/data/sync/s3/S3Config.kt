package me.rerere.rikkahub.data.sync.s3

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.BackupItem
import me.rerere.rikkahub.data.datastore.DEFAULT_BACKUP_ITEMS

@Serializable
data class S3Config(
    val endpoint: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val region: String = "auto",
    val pathStyle: Boolean = true,
    val items: List<BackupItem> = DEFAULT_BACKUP_ITEMS,
) {
    val host: String
        get() = endpoint
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')

    val isHttps: Boolean
        get() = endpoint.startsWith("https://")

    /** endpoint 没写协议时补上 https://，给不需要兼容旧行为的配置用。 */
    fun withHttpsByDefault(): S3Config =
        if (endpoint.isBlank() || "://" in endpoint) this else copy(endpoint = "https://$endpoint")

    fun bucketUrl(): String {
        return if (pathStyle) {
            "${endpoint.trimEnd('/')}/$bucket"
        } else {
            val scheme = if (isHttps) "https://" else "http://"
            "$scheme$bucket.$host"
        }
    }
}
