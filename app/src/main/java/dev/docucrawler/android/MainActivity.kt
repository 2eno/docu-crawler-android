package dev.docucrawler.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.docucrawler.android.ui.DocuCrawlerNavHost
import dev.docucrawler.android.ui.theme.DocuCrawlerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DocuCrawlerTheme {
                DocuCrawlerNavHost()
            }
        }
    }
}
