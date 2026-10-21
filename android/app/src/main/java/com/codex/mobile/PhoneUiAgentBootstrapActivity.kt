package com.codex.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class PhoneUiAgentBootstrapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setBackgroundColor(Color.WHITE)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                textSize = 24f
                text = "Pocket Lobster virtual screen ready\n请根据用户任务启动目标应用"
                contentDescription = text
            },
        )
    }
}
