package io.github.lokarzz.pedometer.sample

import android.os.Bundle
import android.os.PersistableBundle
import androidx.appcompat.app.AppCompatActivity
import io.github.lokarzz.pedometer.Pedometer

class MainActivity : AppCompatActivity() {

    private val pedometer = Pedometer.getInstance()?.register(this)

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState, persistentState)
        pedometer?.getDailySteps()?.subscribe { data ->

        }
    }

}