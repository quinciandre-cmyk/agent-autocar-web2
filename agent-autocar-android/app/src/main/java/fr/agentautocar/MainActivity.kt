package fr.agentautocar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Coque Android : affiche l'application web (assets/index.html) et lui prête le Bluetooth classique
 *  (profil série SPP, celui de l'interface ITS du tachygraphe, règlement UE 2016/799 annexe IC, appendice 13). */
@SuppressLint("MissingPermission", "SetJavaScriptEnabled")
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var socket: BluetoothSocket? = null
    private var essaisPin = 0
    private val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        // Sert les fichiers de l'appli sur une vraie adresse https (les fonds de carte exigent une origine web)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url)) // liens externes : navigateur
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidBT")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onDestroy() {
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript("try{window.$code}catch(e){}", null) }

    inner class Bridge {
        private fun autorise() = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        @JavascriptInterface
        fun appareils(): String {
            if (!autorise()) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) }
                return "[]"
            }
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return "[]"
            val arr = JSONArray()
            for (d in adapter.bondedDevices) arr.put(JSONObject().put("nom", d.name ?: "?").put("adresse", d.address))
            return arr.toString()
        }

        @JavascriptInterface
        fun connecter(adresse: String) {
            if (!autorise() || !Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(adresse)) return
            Thread {
                try {
                    fermer()
                    val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    val dev = adapter.getRemoteDevice(adresse)
                    if (dev.bondState != BluetoothDevice.BOND_BONDED) throw Exception("appareil non appairé dans les réglages Bluetooth")
                    adapter.cancelDiscovery()
                    val s = dev.createRfcommSocketToServiceRecord(spp)
                    s.connect()
                    socket = s
                    js("onTachoEtat('connecte')")
                    val buf = ByteArray(512)
                    while (true) {
                        val n = s.inputStream.read(buf)
                        if (n < 0) break
                        js("onTachoData('" + buf.take(n).joinToString("") { "%02x".format(it) } + "')")
                    }
                    js("onTachoEtat('ferme')")
                } catch (e: Exception) {
                    js("onTachoEtat('erreur : " + (e.message ?: "?").replace(Regex("[^A-Za-z0-9 _.,-]"), "") + "')")
                }
            }.start()
        }

        @JavascriptInterface
        fun envoyer(hex: String): String {
            val s = socket ?: return "non connecté"
            val b = try {
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } catch (e: Exception) { return "hex invalide" }
            // Garde-fou : une trame SendPIN (SID 0x03) est comptée à part. Au-delà de 2 essais, refus.
            if (b.size > 3 && b[3].toInt() == 3) {
                if (essaisPin >= 2) return "refusé : 2 essais de PIN déjà faits, ferme et rouvre l'application"
                essaisPin++
            }
            return try { s.outputStream.write(b); s.outputStream.flush(); "ok" } catch (e: Exception) { "erreur d'envoi" }
        }

        @JavascriptInterface fun essaisPin(): Int = essaisPin
        @JavascriptInterface fun fermer() { try { socket?.close() } catch (_: Exception) {}; socket = null }
    }
}
