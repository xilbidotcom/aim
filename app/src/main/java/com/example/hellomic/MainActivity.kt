// MainActivity.kt
@file:Suppress("SetTextI18n")

package com.example.hellomic

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    /* ─────────────────── 0. Static-asset helper ─────────────────── */
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    /* ─────────────────── 1. Runtime fields ─────────────────── */
    private lateinit var webView: WebView
    private lateinit var audioManager: AudioManager

    private var recorder: AudioRecord? = null
    private var vadThread: Thread?     = null

    /* Toast whenever some *other* app grabs the mic */
    private val micMonitor = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(list: List<AudioRecordingConfiguration>) {
            list.filter { !it.isClientSilenced }.forEach { cfg ->
                val uid = runCatching {
                    cfg.javaClass.getMethod("getClientUid").invoke(cfg) as Int
                }.getOrDefault(Process.INVALID_UID)

                if (uid != Process.myUid() && uid != Process.INVALID_UID) {
                    val pkgs = packageManager.getPackagesForUid(uid) ?: arrayOf("uid:$uid")
                    Toast.makeText(
                        this@MainActivity,
                        "Mic active by ${pkgs.joinToString()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /* ─────────────────── 2. RECORD_AUDIO permission ─────────────────── */
    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setupWebView()
                startNativeVad()
                loadWebPage()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    /* ─────────────────── 3. Activity lifecycle ─────────────────── */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView      = findViewById(R.id.webView)
        audioManager = getSystemService(AudioManager::class.java)

        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onResume() {
        super.onResume()
        audioManager.registerAudioRecordingCallback(
            micMonitor, Handler(Looper.getMainLooper())
        )
    }

    override fun onPause() {
        super.onPause()
        audioManager.unregisterAudioRecordingCallback(micMonitor)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNativeVad()
    }

    /* ─────────────────── 4. Native-side VAD (simple RMS gate) ─────────────────── */
    private fun startNativeVad() {
        if (recorder != null) return        // already running

        val sampleRate   = 16_000           // 16 kHz
        val frameMs      = 10               // 10-ms frames
        val frameSamples = sampleRate * frameMs / 1000  // 160
        val frameBytes   = frameSamples * 2             // 16-bit PCM

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, frameBytes * 4)
        ).apply { startRecording() }

        vadThread = Thread {
            val buf = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
            var talking       = false
            var silenceFrames = 0

            while (!Thread.interrupted() &&
                recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {

                buf.clear()
                val n = recorder!!.read(buf, frameBytes, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue

                /* ─── Very naive RMS gate ─── */
                buf.rewind()
                var sumSq = 0.0
                repeat(frameSamples) { sumSq += (buf.short / 32768.0).let { it * it } }
                val rms = sqrt(sumSq / frameSamples)
                val speech = rms > 0.02          // ⇦ adjust for your mic
                /* ──────────────────────────── */

                if (speech && !talking) {
                    talking = true
                    silenceFrames = 0
                    fireJsEvent("speechStart")
                } else if (!speech && talking) {
                    if (++silenceFrames > 25) {  // ≈250 ms hang-over
                        talking = false
                        fireJsEvent("speechEnd")
                    }
                }
            }
        }.also { it.start() }
    }

    private fun stopNativeVad() {
        vadThread?.interrupt();   vadThread = null
        recorder?.run { stop(); release() }; recorder = null
    }

    /** Sends a DOM CustomEvent called “vad”.  JS:
     *  `window.addEventListener('vad', e => console.log(e.detail))` */
    private fun fireJsEvent(detail: String) {
        val js = "window.dispatchEvent(new CustomEvent('vad',{detail:'$detail'}));"
        webView.post { webView.evaluateJavascript(js, null) }
        Log.d("VAD-NATIVE", detail)
    }

    /* ─────────────────── 5.  WebView (no ONNX patches) ─────────────────── */
    /* ── 5. WebView (no ONNX patches) ── */
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled  = true
            domStorageEnabled  = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort      = true
            cacheMode            = WebSettings.LOAD_NO_CACHE
        }

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                v: WebView, r: WebResourceRequest
            ): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(r.url)
                    ?: super.shouldInterceptRequest(v, r)
        }

        /*  >>> THIS BLOCK auto-grants the page’s audio request  <<<  */
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(req: PermissionRequest) {
                val audio = req.resources.filter {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (audio.isNotEmpty()) req.grant(audio.toTypedArray())
                else req.deny()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            WebView.setWebContentsDebuggingEnabled(true)
    }


    /* ─────────────────── 6.  Kick-off page ─────────────────── */
    private fun loadWebPage() {
        webView.loadUrl("https://sermas.vexlis.com/")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
