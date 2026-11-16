package com.manhy.sonygenezi

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var irManager: ConsumerIrManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        irManager = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(SonyIrBridge(), "AndroidIR")
        setContentView(webView)

        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class SonyIrBridge {

        /**
         * Genera y transmite la trama infrarroja oficial Sony SIRC a 40,000 Hz
         * sin necesidad de clonar ni mapear el control físico RM-AMU006.
         * En el protocolo Sony SIRC, cada comando se repite 3 veces por pulsación.
         */
        @JavascriptInterface
        fun transmitSonySirc(bits: Int, address: Int, command: Int): Boolean {
            val manager = irManager ?: return false
            if (!manager.hasIrEmitter()) return false

            val singleFrame = mutableListOf<Int>()
            // Pulso de inicio (Header): 2400 us ON, 600 us OFF
            singleFrame.add(2400)
            singleFrame.add(600)

            // 7 bits de comando (LSB primero)
            for (i in 0 until 7) {
                val bit = (command shr i) and 1
                singleFrame.add(if (bit == 1) 1200 else 600)
                singleFrame.add(600)
            }

            // Bits de dirección (5 bits para SIRC-12, 8 bits para SIRC-15, LSB primero)
            val addrBits = if (bits == 15) 8 else 5
            for (i in 0 until addrBits) {
                val bit = (address shr i) and 1
                singleFrame.add(if (bit == 1) 1200 else 600)
                singleFrame.add(600)
            }

            // Pausa final entre repeticiones (~25 ms para completar ventana de 45 ms)
            singleFrame[singleFrame.lastIndex] = 25000

            // Repetir 3 tramas como exige el receptor Sony Genezi MHC-GTX88
            val fullPattern = IntArray(singleFrame.size * 3)
            for (rep in 0 until 3) {
                for ( idx in singleFrame.indices) {
                    fullPattern[rep * singleFrame.size + idx] = singleFrame[idx]
                }
            }

            return try {
                manager.transmit(40000, fullPattern)
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun sendWifiCommand(ip: String, port: String, cmdName: String, bits: Int, address: Int, command: Int) {
            thread {
                try {
                    val url = URL("http://$ip:$port/ir?cmd=$cmdName&bits=$bits&addr=$address&code=$command")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }
}
