package com.example.bifurcation_app_android

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {

    // 1. Declare the SerialManager globally so it lives as long as the Activity
    private lateinit var serialManager: SerialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2. Initialize SerialManager. This is the ONLY instance of this class.
        // We pass 'this' (the activity) so it can access USB system services.
        serialManager = SerialManager(this)

        enableEdgeToEdge()

        setContent {
            // Get the current view context for status bar styling
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as android.app.Activity).window
                    window.statusBarColor = android.graphics.Color.parseColor("#0088d6")
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                }
            }

            Surface(modifier = Modifier.fillMaxSize()) {
                // Pass the single serialManager instance down to the WebView Composable
                MyVitalsAppWebView(
                    url = "https://clinic-setup-git-features-printer-ezshifas-projects.vercel.app/",
                    serialManager = serialManager
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 3. Safety first: If the app is closed, kill the USB connection
        serialManager.disconnect()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MyVitalsAppWebView(url: String, serialManager: SerialManager) {
    // State to hold the WebView reference for JS evaluation
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoading  by remember { mutableStateOf(true) }
    var isOffline  by remember { mutableStateOf(false) }

    // Access the Activity context from inside the Composable
    val activity = LocalView.current.context as ComponentActivity

    // Handle the Android back button: Go back in web history if possible
    BackHandler {
        if (webViewRef?.canGoBack() == true) webViewRef?.goBack()
        else activity.finish()
    }

    // AUTO-CONNECT LOGIC: Triggered as soon as the WebView is initialized
    LaunchedEffect(webViewRef) {
        if (webViewRef != null) {
            serialManager.connect { data ->
                // This listener receives data from the SerialManager (ESP32)
                webViewRef?.post {
                    // Send the data to a global JS function 'window.onSerialData'
                    webViewRef?.evaluateJavascript(
                        "javascript:if(window.onSerialData){ window.onSerialData('$data'); }",
                        null
                    )
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        // Status bar background color
        Box(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).background(Color(0xFF0088d6)))

        Box(modifier = Modifier.weight(1f)) {
            if (isOffline) {
                // UI for when the website fails to load (Offline Mode)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        isOffline = false
                        isLoading = true
                        webViewRef?.reload()
                    }) { Text("Retry Connection") }
                }
            } else {
                // THE CORE WEBVIEW COMPONENT
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            // Configure WebView behavior
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) { isLoading = true }
                                override fun onPageFinished(v: WebView?, u: String?) { isLoading = false }
                                override fun onReceivedError(v: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                                    if (req?.isForMainFrame == true) {
                                        isOffline = true
                                        isLoading = false
                                    }
                                }
                            }

                            // Essential WebView Settings
                            settings.apply {
                                javaScriptEnabled = true // Required for the Bridge
                                domStorageEnabled = true // Required for Next.js/React
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }

                            // --- THE BRIDGE CONNECTION ---

                            // 1. Create the Bridge and link it to the EXISTING serialManager
                            val bridge = AndroidBridge(activity, this, serialManager)

                            // 2. Register the Bridge.
                            // This creates 'window.AndroidNative' in your website's JavaScript.
                            addJavascriptInterface(bridge, "AndroidNative")

                            // --- END BRIDGE CONNECTION ---

                            loadUrl(url)
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Show a progress spinner while the Vercel site is loading
            if (isLoading && !isOffline) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF007EAF))
                }
            }
        }
    }
}