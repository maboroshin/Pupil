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

package xyz.quaver.pupil.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import kotlinx.android.synthetic.main.item_reader.view.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.quaver.Code
import xyz.quaver.hitomi.Reader
import xyz.quaver.hitomi.getReferer
import xyz.quaver.hitomi.imageUrlFromImage
import xyz.quaver.hiyobi.cookie
import xyz.quaver.hiyobi.createImgList
import xyz.quaver.hiyobi.user_agent
import xyz.quaver.pupil.R
import xyz.quaver.pupil.util.download.Cache
import xyz.quaver.pupil.util.download.DownloadWorker
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.roundToInt

class ReaderAdapter(private val glide: RequestManager,
                    private val galleryID: Int,
                    private val activity: Activity) : RecyclerView.Adapter<ReaderAdapter.ViewHolder>() {

    var reader: Reader? = null
    val timer = Timer()

    var isFullScreen = false

    var onItemClickListener : ((Int) -> (Unit))? = null

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)

    var downloadWorker: DownloadWorker? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return LayoutInflater.from(parent.context).inflate(
            R.layout.item_reader, parent, false
        ).let {
            ViewHolder(it)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.view as ConstraintLayout

        if (downloadWorker == null)
            downloadWorker = DownloadWorker.getInstance(holder.view.context)

        if (isFullScreen) {
            holder.view.layoutParams.height = RecyclerView.LayoutParams.MATCH_PARENT
            holder.view.container.layoutParams.height = ConstraintLayout.LayoutParams.MATCH_PARENT
        } else {
            holder.view.layoutParams.height = RecyclerView.LayoutParams.WRAP_CONTENT
            holder.view.container.layoutParams.height = 0

            (holder.view.container.layoutParams as ConstraintLayout.LayoutParams)
                .dimensionRatio = "W,${reader!!.galleryInfo.files[position].width}:${reader!!.galleryInfo.files[position].height}"
        }

        holder.view.image.setOnPhotoTapListener { _, _, _ ->
            onItemClickListener?.invoke(position)
        }

        holder.view.container.setOnClickListener {
            onItemClickListener?.invoke(position)
        }

        holder.view.reader_index.text = (position+1).toString()

        val preferences = PreferenceManager.getDefaultSharedPreferences(holder.view.context)
        if (preferences.getBoolean("cache_disable", false)) {
            val lowQuality = preferences.getBoolean("low_quality", false)

            val url = when (reader!!.code) {
                Code.HITOMI ->
                    GlideUrl(
                        imageUrlFromImage(
                            galleryID,
                            reader!!.galleryInfo.files[position],
                            !lowQuality
                        )
                    , LazyHeaders.Builder().addHeader("Referer", getReferer(galleryID)).build())
                Code.HIYOBI ->
                    GlideUrl(createImgList(galleryID, reader!!, lowQuality)[position].path, LazyHeaders.Builder()
                        .addHeader("User-Agent", user_agent)
                        .addHeader("Cookie", cookie)
                        .build())
                else -> null
            }
            holder.view.image.post {
                glide
                    .load(url!!)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(false)
                    .fitCenter()
                    .error(R.drawable.image_broken_variant)
                    .into(holder.view.image)
            }
        } else {
            val image = Cache(holder.view.context).getImage(galleryID, position)
            val progress = downloadWorker!!.progress[galleryID]?.get(position)

            if (progress?.isInfinite() == true && image != null) {
                holder.view.reader_item_progressbar.visibility = View.INVISIBLE

                holder.view.image.post {
                    glide
                        .load(image)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .fitCenter()
                        .error(R.drawable.image_broken_variant)
                        .into(holder.view.image)
                }

            } else {
                holder.view.reader_item_progressbar.visibility = View.VISIBLE

                glide.clear(holder.view.image)

                holder.view.reader_item_progressbar.progress =
                    if (progress?.isInfinite() == true)
                        100
                    else
                        progress?.roundToInt() ?: 0

                holder.view.image.setImageDrawable(null)

                timer.schedule(1000) {
                    CoroutineScope(Dispatchers.Main).launch {
                        notifyItemChanged(position)
                    }
                }
            }
        }
    }

    override fun getItemCount() = reader?.galleryInfo?.files?.size ?: 0

}