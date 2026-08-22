package com.openminis.app.integration

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.theme.MinisTheme

class SharedBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pool = SharedMinisRuntime.browser(this)
        pool.ensureTabForUI()
        setContent {
            MinisTheme {
                BrowserSheet(
                    tabPool = pool,
                    onDismiss = { finish() },
                )
            }
        }
    }
}
