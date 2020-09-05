/*
 *     Pupil, Hitomi.la viewer for Android
 *     Copyright (C) 2019  tom5079
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package xyz.quaver.pupil.util

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.URLUtil
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import ru.noties.markwon.Markwon
import xyz.quaver.hitomi.GalleryBlock
import xyz.quaver.hitomi.Reader
import xyz.quaver.io.FileX
import xyz.quaver.io.util.getChild
import xyz.quaver.io.util.*
import xyz.quaver.pupil.BuildConfig
import xyz.quaver.pupil.R
import xyz.quaver.pupil.client
import xyz.quaver.pupil.services.DownloadService
import xyz.quaver.pupil.util.downloader.Cache
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.*

fun getReleases(url: String) : JsonArray {
    return try {
        URL(url).readText().let {
            Json.parseToJsonElement(it).jsonArray
        }
    } catch (e: Exception) {
        JsonArray(emptyList())
    }
}

fun checkUpdate(url: String) : JsonObject? {
    val releases = getReleases(url)

    if (releases.isEmpty())
        return null

    return releases.firstOrNull {
        Preferences["beta"] || it.jsonObject["prerelease"]?.jsonPrimitive?.booleanOrNull == false
    }?.let {
        if (it.jsonObject["tag_name"]?.jsonPrimitive?.contentOrNull == BuildConfig.VERSION_NAME)
            null
        else
            it.jsonObject
    }
}

fun getApkUrl(releases: JsonObject) : String? {
    return releases["assets"]?.jsonArray?.firstOrNull {
        Regex("Pupil-v.+\\.apk").matches(it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "")
    }.let {
        it?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
    }
}

fun checkUpdate(context: Context, force: Boolean = false) {

    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    val ignoreUpdateUntil = preferences.getLong("ignore_update_until", 0)

    if (!force && ignoreUpdateUntil > System.currentTimeMillis())
        return

    fun extractReleaseNote(update: JsonObject, locale: Locale) : String {
        val markdown = update["body"]!!.jsonPrimitive.content

        val target = when(locale.language) {
            "ko" -> "한국어"
            "ja" -> "日本語"
            else -> "English"
        }

        val releaseNote = Regex("^# Release Note.+$")
        val language = Regex("^## $target$")
        val end = Regex("^#.+$")

        var releaseNoteFlag = false
        var languageFlag = false

        val result = StringBuilder()

        for(line in markdown.lines()) {
            if (releaseNote.matches(line)) {
                releaseNoteFlag = true
                continue
            }

            if (releaseNoteFlag) {
                if (language.matches(line)) {
                    languageFlag = true
                    continue
                }
            }

            if (languageFlag) {
                if (end.matches(line))
                    break

                result.append(line+"\n")
            }
        }

        return context.getString(R.string.update_release_note, update["tag_name"]?.jsonPrimitive?.contentOrNull, result.toString())
    }

    CoroutineScope(Dispatchers.Default).launch {
        val update =
            checkUpdate(context.getString(R.string.release_url)) ?: return@launch

        val url = getApkUrl(update) ?: return@launch

        val dialog = AlertDialog.Builder(context).apply {
            setTitle(R.string.update_title)
            val msg = extractReleaseNote(update, Locale.getDefault())
            setMessage(Markwon.create(context).toMarkdown(msg))
            setPositiveButton(android.R.string.yes) { _, _ ->

                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

                //Cancel any download queued before

                val id: Long = Preferences["update_download_id"]

                if (id != -1L)
                    downloadManager.remove(id)

                val target = File(context.getExternalFilesDir(null), "Pupil.apk").also {
                    it.delete()
                }

                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle(context.getText(R.string.update_notification_description))
                    .setDestinationUri(Uri.fromFile(target))

                downloadManager.enqueue(request).also {
                    Preferences["update_download_id"] = it
                }
            }
            setNegativeButton(if (force) android.R.string.no else R.string.ignore_update) { _, _ ->
                if (!force)
                    preferences.edit()
                        .putLong("ignore_update_until", System.currentTimeMillis() + 604800000)
                        .apply()
            }
        }

        launch(Dispatchers.Main) {
            dialog.show()
        }
    }
}

fun restore(favorites: GalleryList, url: String, onFailure: ((Throwable) -> Unit)? = null, onSuccess: ((List<Int>) -> Unit)? = null) {
    if (!URLUtil.isValidUrl(url)) {
        onFailure?.invoke(IllegalArgumentException())
        return
    }

    val request = Request.Builder()
        .url(url)
        .get()
        .build()

    client.newCall(request).enqueue(object: Callback {
        override fun onFailure(call: Call, e: IOException) {
            onFailure?.invoke(e)
        }

        override fun onResponse(call: Call, response: Response) {
            kotlin.runCatching {
                Json.decodeFromString<List<Int>>(response.body().use { it?.string() } ?: "[]").let {
                    favorites.addAll(it)
                    onSuccess?.invoke(it)
                }
            }.onFailure { onFailure?.invoke(it) }
        }
    })
}

private var job: Job? = null
private val receiver = object: BroadcastReceiver() {
    val ACTION_CANCEL = "ACTION_IMPORT_CANCEL"
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return

        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                NotificationManagerCompat.from(context).cancel(R.id.notification_id_import)
            }
        }
    }
}
@SuppressLint("RestrictedApi")
fun xyz.quaver.pupil.util.downloader.DownloadManager.migrate() {
    val notificationManager = NotificationManagerCompat.from(this)
    val action = NotificationCompat.Action.Builder(0, getText(android.R.string.cancel),
        PendingIntent.getBroadcast(this, R.id.notification_import_cancel_action.normalizeID(), Intent(receiver.ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT)
    ).build()
    val notification = NotificationCompat.Builder(this, "import")
        .setContentTitle(getText(R.string.import_old_galleries_notification))
        .setProgress(0, 0, true)
        .addAction(action)
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)

    DownloadService.cancel(this)

    job?.cancel()
    job = CoroutineScope(Dispatchers.IO).launch {
        val folders = downloadFolder.listFiles { folder ->
            (folder as? FileX)?.isDirectory == true && !downloadFolderMap.values.contains(folder.name)
        }
        if (folders.isNullOrEmpty()) return@launch
        folders.forEachIndexed { index, folder ->
            notification
                .setContentText(getString(R.string.import_old_galleries_notification_text, index, folders.size))
                .setProgress(index, folders.size, false)
            notificationManager.notify(R.id.notification_id_import, notification.build())

            kotlin.runCatching {
                val folder = (folder as? FileX) ?: return@runCatching

                val metadata = folder.getChild(".metadata").readText()?.let { Json.parseToJsonElement(it).jsonObject } ?: return@runCatching

                val galleryBlock: GalleryBlock? =
                    metadata["galleryBlock"]?.let { Json.decodeFromJsonElement<GalleryBlock>(it) }
                val reader: Reader? =
                    metadata["reader"]?.let { Json.decodeFromJsonElement<Reader>(it) }

                val galleryID = galleryBlock?.id ?: reader?.galleryInfo?.id ?: folder.name.toIntOrNull() ?: return@runCatching

                metadata["thumbnail"]?.jsonPrimitive?.contentOrNull.let { thumbnail ->
                    val file = folder.getChild(".thumbnail").also {
                        if (!it.exists())
                            it.createNewFile()
                    }

                    file.writeBytes(Base64.decode(thumbnail, Base64.DEFAULT))
                }

                downloadFolderMap[galleryID] = folder.name

                val cache = Cache.getInstance(this@migrate, galleryID)

                val list: MutableList<String?> =
                    MutableList(cache.getReader()!!.galleryInfo.files.size) { null }

                folder.listFiles { dir ->
                    dir?.nameWithoutExtension?.toIntOrNull() != null
                }?.forEach {
                    list[it.nameWithoutExtension.toInt()] = it.name
                }

                cache.setMetadata {
                    it.galleryBlock = galleryBlock
                    it.reader = reader
                    it.imageList = list
                }
            }
        }

        notification
            .setContentText(getText(R.string.import_old_galleries_notification_done))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .mActions.clear()
        notificationManager.notify(R.id.notification_id_import, notification.build())
    }
}