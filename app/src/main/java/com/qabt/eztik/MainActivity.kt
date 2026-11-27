package com.qabt.eztik

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.qabt.eztik.ui.TikTokTab

/** Independent Android entrypoint, with no ezTube navigation or account modules. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TikTokTab(padding = PaddingValues(0.dp))
            }
        }
    }
}
