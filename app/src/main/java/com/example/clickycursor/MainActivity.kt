package com.example.clickycursor

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "clicky_prefs"
        const val KEY_CURSOR_SPEED = "cursor_speed"
        const val MIN_SPEED = 2f
        const val MAX_SPEED = 20f
        const val DEFAULT_SPEED = 7f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val seekBar = findViewById<SeekBar>(R.id.seekSpeed)
        val valueLabel = findViewById<TextView>(R.id.tvSpeedValue)

        val range = (MAX_SPEED - MIN_SPEED).toInt()
        seekBar.max = range

        val savedSpeed = prefs.getFloat(KEY_CURSOR_SPEED, DEFAULT_SPEED)
        seekBar.progress = (savedSpeed - MIN_SPEED).toInt().coerceIn(0, range)
        valueLabel.text = "Speed: ${savedSpeed.toInt()}"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSpeed = MIN_SPEED + progress
                valueLabel.text = "Speed: ${newSpeed.toInt()}"
                prefs.edit().putFloat(KEY_CURSOR_SPEED, newSpeed).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
}
