package com.abdownloadmanager.desktop.pages.videoquality

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.abdownloadmanager.desktop.AppComponent
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.ui.widget.NotificationType
import com.abdownloadmanager.shared.ui.widget.ShowNotification
import ir.amirab.util.compose.asStringSource

/**
 * A notification with the progress of every video that yt-dlp is downloading (HLS and DASH streams).
 * Each one disappears by itself when the video is finished.
 */
@Composable
fun VideoJobsNotification(
    appComponent: AppComponent,
) {
    val jobs by appComponent.videoJobs.collectAsState()
    jobs.forEach { job ->
        key(job.id) {
            ShowNotification(
                title = Res.string.video_job_title.asStringSource(),
                description = "${job.title} - ${job.percent}%".asStringSource(),
                type = NotificationType.Loading(job.percent),
                tag = "video-job-${job.id}",
            )
        }
    }
}
