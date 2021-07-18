package io.github.lokarzz.pedometer.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.lokarzz.pedometer.Pedometer
import io.github.lokarzz.pedometer.R

class PedometerWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {


    override suspend fun doWork(): Result {

        val title = inputData.getString(TITLE)
        val contextText = inputData.getString(CONTEXT_TEXT)
        val smallIcon = inputData.getInt(SMALL_ICON, R.drawable.ic_walk)

        val pedometer = Pedometer.instance ?: return Result.failure()

        setForeground(createForegroundInfo(title, contextText, smallIcon))

        pedometer.startStepsTracking()

        return Result.success()

    }

    private fun createForegroundInfo(
        title: String?,
        contextText: String?,
        @DrawableRes smallIcon: Int?
    ): ForegroundInfo {
        val id = "0"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel()
        }

        val notification = NotificationCompat.Builder(applicationContext, id)
            .setContentTitle(title)
            .setContentText(contextText)
            .setSmallIcon(smallIcon ?: 0)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(0, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        } else {
            ForegroundInfo(0, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
    }

    companion object {
        const val TITLE = "title"
        const val CONTEXT_TEXT = "context_text"
        const val SMALL_ICON = "small_icon"
    }
}