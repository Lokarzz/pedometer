package io.github.lokarzz.pedometer

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.work.*
import com.google.gson.Gson
import io.github.lokarzz.pedometer.Pedometer.Result.PEDOMETER_INSTANCE_NOT_INITIALIZE
import io.github.lokarzz.pedometer.pojo.PedometerData
import io.github.lokarzz.pedometer.pojo.Steps
import io.github.lokarzz.pedometer.service.PedometerWorker
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import java.lang.NullPointerException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class Pedometer(private val application: Application) {

    private var activityResultLauncher: ActivityResultLauncher<Array<out String>>? = null
    private var singleEmitter: SingleEmitter<Boolean?>? = null


    fun register(appCompatActivity: AppCompatActivity? = null, fragment: Fragment? = null) = apply {
        fragment?.let {
            this.activityResultLauncher = it.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(), resultCallBack()
            )
        }
        appCompatActivity?.let {
            this.activityResultLauncher = it.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
                resultCallBack()
            )
        }
    }

    fun requestPermission(): Single<Boolean> {
        return Single.create {
            if (activityResultLauncher == null) {
                it.onError(Throwable(Result.NOT_REGISTERED))
            }
            singleEmitter = it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityResultLauncher?.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
            } else {
                singleEmitter?.onSuccess(true)
            }
        }
    }

    private fun resultCallBack(): ActivityResultCallback<MutableMap<String, Boolean>> {
        return (ActivityResultCallback<MutableMap<String, Boolean>> {
            singleEmitter ?: return@ActivityResultCallback
            var success = true
            for (entry in it) {
                success = entry.value
                if (!success) {
                    break
                }
            }
            singleEmitter?.onSuccess(success)

        })
    }

    fun startStepsTracking() {
        val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        stepCounterSensor?.let {
            sensorManager.registerListener(
                sensorEventListener(),
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
    }

    fun trackSteps(listener: Pedometer.Listener?) {
        val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        stepCounterSensor?.let {
            sensorManager.registerListener(
                object : SensorEventListener {
                    override fun onSensorChanged(sensorEvent: SensorEvent) {
                        listener?.onStepChange(getInt(sensorEvent.values[0]))
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                        // do nothing
                    }

                },
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
    }

    private fun sensorEventListener(): SensorEventListener {
        val pedometerData = currentStepsData()

        return object : SensorEventListener {
            override fun onSensorChanged(sensorEvent: SensorEvent) {
                if (sensorEvent.sensor.type != Sensor.TYPE_STEP_COUNTER) {
                    return
                }

                val sensorStepValue = getInt(sensorEvent.values[0])

                //init
                if (pedometerData.stepsOnLastTimeStamp == 0) {
                    pedometerData.stepsOnLastTimeStamp = sensorStepValue
                }

                //for phone restart
                if (pedometerData.stepsOnLastTimeStamp > sensorStepValue) {
                    pedometerData.stepsOnLastTimeStamp = 0
                }

                if (pedometerData.stepsOnLastTimeStamp == sensorStepValue) {
                    saveData(pedometerData)
                    return
                }

                val steps = sensorStepValue - pedometerData.stepsOnLastTimeStamp
                val timeStamp = getTimeStamp(sensorEvent.timestamp)

                var stepData =
                    if (pedometerData.stepsData.isNullOrEmpty()) null else pedometerData.stepsData?.last();

                if (stepData == null || stepData.timeStamp != timeStamp) {
                    stepData = Steps(timeStamp, steps);
                    pedometerData.stepsData?.add(stepData)
                } else {
                    stepData.steps += steps
                }

                pedometerData.stepsOnLastTimeStamp = sensorStepValue

                saveData(pedometerData)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

            }

        }
    }

    fun isGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                application,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun getTimeStamp(sensorTimeStamp: Long): Long? {
        val lastDeviceBootTimeInMillis =
            System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val sensorEventTimeInMillis = sensorTimeStamp / 1000_000
        val actualSensorEventTimeInMillis =
            lastDeviceBootTimeInMillis + sensorEventTimeInMillis
        val displayDateStr =
            SimpleDateFormat("yyyy-MM-dd HH", Locale.getDefault()).format(
                actualSensorEventTimeInMillis
            )

        return getDateToMillis(displayDateStr, "yyyy-MM-dd HH");
    }

    private fun getDateToMillis(date: String, format: String?): Long? {
        val sdf = SimpleDateFormat(format, Locale.getDefault())
        var timeInMilliseconds: Long = 0
        try {
            val parsedDate = sdf.parse(date)
            timeInMilliseconds = parsedDate?.time ?: 0
        } catch (e: java.lang.Exception) {
            // do nothing
        }
        return timeInMilliseconds
    }


    fun getDailySteps(
    ): Single<ArrayList<Steps>> {
        val calendarStart = Calendar.getInstance(Locale.getDefault())
        calendarStart.set(Calendar.HOUR_OF_DAY, 0)
        calendarStart.set(Calendar.MINUTE, 0)
        calendarStart.set(Calendar.SECOND, 0)
        calendarStart.set(Calendar.MILLISECOND, 0)
        val startTime = calendarStart.timeInMillis


        val calendarEnd = Calendar.getInstance(Locale.getDefault())
        val endTime = calendarEnd.timeInMillis
        return getSteps(startTime, endTime)
    }

    fun getSteps(
        startTimeMillis: Long,
        endTimeMillis: Long,
    ): Single<ArrayList<Steps>> {

        return Single.create {
            val pedometerData = currentStepsData()

            Observable.fromIterable(pedometerData.stepsData)
                .subscribeOn(Schedulers.newThread())
                .observeOn(Schedulers.newThread())
                .filter { it.timeStamp in startTimeMillis..endTimeMillis }
                .toList()
                .subscribe { data ->
                    it.onSuccess(data as ArrayList<Steps>)
                }
        }
    }


    fun startBackGroundTracking(
        title: String?,
        contextText: String?,
        @DrawableRes smallIcon: Int?
    ) {
        val inputData: Data = Data.Builder()
            .putString(PedometerWorker.TITLE, title)
            .putString(PedometerWorker.CONTEXT_TEXT, contextText)
            .putInt(PedometerWorker.SMALL_ICON, smallIcon ?: 0)
            .build()

        val workRequest: PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<PedometerWorker>(15, TimeUnit.MINUTES)
                .setInputData(inputData)
                .build()

        WorkManager.getInstance(application).enqueueUniquePeriodicWork(
            KEY,
            ExistingPeriodicWorkPolicy.KEEP, workRequest
        )
    }

    fun stopBackGroundTracking() {
        WorkManager.getInstance(application).cancelAllWorkByTag(KEY);
    }

    private fun currentStepsData(): PedometerData {
        val sharedPreferences: SharedPreferences =
            application.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        val pedometerJson = sharedPreferences.getString(PedometerData::class.simpleName, "")

        val pedometerData = getGson(pedometerJson, PedometerData::class.java)



        return pedometerData ?: PedometerData()
    }

    private fun saveData(pedometerData: PedometerData) {
        val editor = application.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        editor.putString(
            PedometerData::class.simpleName,
            getGsonString(pedometerData)
        )
        editor.apply()
    }


    private fun <T> getGson(json: String?, clazz: Class<T>?): T? {
        val gson = Gson()
        var ret: T? = null
        try {
            ret = gson.fromJson(json, clazz)
        } catch (e: Exception) {
            // do nothing
        }
        return ret
    }

    private fun getGsonString(`object`: Any?): String? {
        var ret: String? = ""
        try {
            ret = Gson().toJson(`object`)
        } catch (e: java.lang.Exception) {
            if (`object` !is String) e.stackTrace
        }
        return ret
    }


    private fun getInt(value: Float): Int {
        var ret = 0
        try {
            ret = value.toInt()
        } catch (e: java.lang.Exception) {
            // do nothing
        }
        return ret
    }

    interface Listener {
        fun onStepChange(steps: Int?)
    }


    companion object {
        const val KEY: String = "PEDOMETER_KEY"
        const val FILE: String = "io.github.lokarzz.pedometer"

        private var instance: Pedometer? = null

        fun initialize(application: Application) {
            instance = Pedometer(application)
        }

        fun getInstance(): Pedometer? {
            return if (instance == null) {
                throw NullPointerException(PEDOMETER_INSTANCE_NOT_INITIALIZE)
            } else {
                instance
            }
        }
    }

    object Result {
        const val NOT_REGISTERED = "not_registered"
        const val PEDOMETER_INSTANCE_NOT_INITIALIZE = "pedometer_instance_not_initialize"
    }
}