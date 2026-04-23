package com.example.bifurcation_app_android

import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.widget.Toast
import android.webkit.WebView
import androidx.activity.ComponentActivity

class AndroidBridge(
    private val activity: ComponentActivity,
    private val webView: WebView,
    private val serialManager: SerialManager
) {

    private val medicineService = MedicineService(serialManager)
    private val printerManager = BluetoothPrinterManager(activity)

    // ─────────────────────────────────────────────────────────────────────────
    // PRINTER DISCOVERY & SELECTION  (called from React)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a JSON array of paired Bluetooth devices to the web layer.
     * React listens on window.onPrintersLoaded(json).
     *
     * Called by: BluetoothPrinterModal → "Refresh" or on first open.
     */
    @JavascriptInterface
    fun getPairedPrinters() {
        // Bluetooth permission guard (Android 12+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    activity, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                sendToWeb("onPrintersLoaded", """{"error":"PERMISSION_DENIED","devices":[]}""")
                return
            }
        }

        try {
            val devices = printerManager.getPairedPrinters()
            val arr = JSONArray()
            for (device in devices) {
                val obj = JSONObject()
                @Suppress("MissingPermission")
                obj.put("name", device.name ?: "Unknown Device")
                obj.put("address", device.address)
                arr.put(obj)
            }
            val payload = JSONObject()
            payload.put("error", JSONObject.NULL)
            payload.put("devices", arr)
            sendToWeb("onPrintersLoaded", payload.toString())
        } catch (e: SecurityException) {
            sendToWeb("onPrintersLoaded", """{"error":"PERMISSION_DENIED","devices":[]}""")
        } catch (e: Exception) {
            Log.e("BRIDGE_ERROR", "getPairedPrinters failed: ${e.message}")
            sendToWeb("onPrintersLoaded", """{"error":"${e.message}","devices":[]}""")
        }
    }

    /**
     * Saves the chosen printer MAC address and fires a test connection.
     * React listens on window.onPrinterSelected(success, message).
     *
     * @param address  Bluetooth MAC address, e.g. "AA:BB:CC:DD:EE:FF"
     * Called by: BluetoothPrinterModal → user taps a printer row.
     */
    @JavascriptInterface
    fun selectPrinter(address: String) {
        Thread {
            try {
                printerManager.savePrinter(address)

                // Lightweight connection test: init + flush, no actual data
                val testResult = printerManager.testConnection(address)
                if (testResult) {
                    sendPrinterSelectedToWeb(true, "Connected")
                } else {
                    sendPrinterSelectedToWeb(false, "Could not reach printer. Check power/range.")
                }
            } catch (e: Exception) {
                Log.e("BRIDGE_ERROR", "selectPrinter failed: ${e.message}")
                sendPrinterSelectedToWeb(false, e.message ?: "Unknown error")
            }
        }.start()
    }

    private fun sendPrinterSelectedToWeb(success: Boolean, message: String) {
        val safeMsg = message.replace("'", "\\'")
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onPrinterSelected){window.onPrinterSelected($success,'$safeMsg');}",
                null
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRINTING
    // ─────────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun sendMedicinePacket(jsonString: String) {
        try {
            medicineService.processCommand(jsonString)
        } catch (e: Exception) {
            Log.e("BRIDGE_ERROR", "Failed to forward packet: ${e.message}")
        }
    }

    @JavascriptInterface
    fun connectUsb() {
        val result = serialManager.connect { rawData ->
            sendToWeb("onSerialData", rawData)
        }
        sendToWeb("onUsbStatus", result)
    }

    /** Handles Base64 Image Printing */
    @JavascriptInterface
    fun printImage(base64Data: String) {
        try {
            val decodedString = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)

            if (bitmap != null) {
                val success = printerManager.connectAndPrintBitmap(bitmap)
                if (!success) {
                    activity.runOnUiThread {
                        showPrinterSelector(null, bitmap)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PRINTER_ERROR", "Image decoding failed: ${e.message}")
        }
    }

    /** Legacy/Text Printing */
    @JavascriptInterface
    fun printReceipt(content: String) {
        val success = printerManager.connectAndPrint(content)
        if (!success) {
            activity.runOnUiThread {
                showPrinterSelector(content, null)
            }
        }
    }

    /**
     * Thermal JSON Printing — called by handlePrescriptionPrint in React.
     * Runs entirely on a background thread; no UI blocking.
     */
    @JavascriptInterface
    fun printThermal(jsonData: String) {
        Thread {
            try {
                val json = JSONObject(jsonData)
                val sb = StringBuilder()

                // Header
                val clinicName = json.optString("clinicName", "EZShifa Digital Health")
                sb.append("\n      $clinicName\n")
                sb.append("--------------------------------\n")
                sb.append("Date: ${json.optString("date", "N/A")}\n")
                sb.append("Token: #${json.optString("token", "N/A")}\n")
                sb.append("--------------------------------\n")

                // Patient Info
                val patient = json.optJSONObject("patient") ?: JSONObject()
                sb.append("Patient: ${patient.optString("name", "N/A")}\n")
                sb.append("Age/Sex: ${patient.optString("ageSex", "N/A")}\n")

                // Vitals (Dynamic object iteration)
                val vitals = json.optJSONObject("vitals")
                if (vitals != null && vitals.length() > 0) {
                    sb.append("Vitals:\n")
                    val keys = vitals.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        sb.append("  - $key: ${vitals.optString(key)}\n")
                    }
                }

                // Diagnosis
                val diagnosis = json.optString("diagnosis", "")
                if (diagnosis.isNotEmpty()) {
                    sb.append("Diag:    $diagnosis\n")
                }

                // Lab Tests
                val labTests = json.optJSONArray("labTests")
                if (labTests != null && labTests.length() > 0) {
                    sb.append("Lab Tests:\n")
                    for (i in 0 until labTests.length()) {
                        sb.append("  - ${labTests.optString(i)}\n")
                    }
                }

                // Notes
                val notes = json.optString("notes", "")
                if (notes.isNotEmpty()) {
                    sb.append("Notes:\n  $notes\n")
                }

                sb.append("--------------------------------\n\n")

                // Rx (Medicines)
                sb.append("Rx:\n")
                val medicines = json.optJSONArray("medicines")
                if (medicines != null && medicines.length() > 0) {
                    for (i in 0 until medicines.length()) {
                        val med = medicines.optJSONObject(i) ?: continue
                        sb.append("${i + 1}. ${med.optString("name", "Unknown")}\n")
                        sb.append("   ${med.optString("dosage", "N/A")} (${med.optString("duration", "N/A")})\n")
                        sb.append("   ${med.optString("schedule", "N/A")} | ${med.optString("meal", "N/A")}\n\n")
                    }
                } else {
                    sb.append("  None prescribed\n\n")
                }

                sb.append("--------------------------------\n")

                // Doctor Info
                val doctor = json.optJSONObject("doctor") ?: JSONObject()
                sb.append("Dr. ${doctor.optString("name", "N/A")}\n")

                val specialization = doctor.optString("specialization", "")
                if (specialization.isNotEmpty()) sb.append("$specialization\n")

                val qualifications = doctor.optString("qualifications", "")
                if (qualifications.isNotEmpty()) sb.append("$qualifications\n")

                sb.append("\n\n\n")

                val finalContent = sb.toString()

                val success = printerManager.connectAndPrint(finalContent)
                if (!success) {
                    activity.runOnUiThread { showPrinterSelector(finalContent, null) }
                }
                sendPrintResultToWeb(success, if (success) "Sent to printer" else "Print failed")

            } catch (e: Exception) {
                Log.e("BRIDGE_ERROR", "printThermal failed: ${e.message}")
                sendPrintResultToWeb(false, "Formatting error: ${e.message}")
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun showPrinterSelector(pendingText: String?, pendingBitmap: android.graphics.Bitmap?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Bluetooth permission required", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        val allDevices = try { printerManager.getPairedPrinters() } catch (e: SecurityException) { emptyList() }
        val printerDevices = allDevices.filter {
            it.name?.contains("Bluetooth Printer", ignoreCase = true) == true ||
                    it.name?.contains("MPT-II", ignoreCase = true) == true
        }

        if (printerDevices.isEmpty()) {
            activity.runOnUiThread {
                Toast.makeText(activity, "Printer not found. Pair it in settings.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val targetDevice = printerDevices.first()
        Thread {
            try {
                printerManager.savePrinter(targetDevice.address)
                if (pendingBitmap != null) printerManager.connectAndPrintBitmap(pendingBitmap)
                else if (pendingText != null) printerManager.connectAndPrint(pendingText)

                activity.runOnUiThread {
                    Toast.makeText(activity, "Printing to ${targetDevice.name}...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("PRINTER_ERROR", "Print failed: ${e.message}")
                activity.runOnUiThread {
                    Toast.makeText(activity, "Print failed. Check printer power.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun sendPrintResultToWeb(success: Boolean, message: String) {
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onPrintResult){window.onPrintResult($success,'$message');}",
                null
            )
        }
    }

    private fun sendToWeb(functionName: String, data: String) {
        activity.runOnUiThread {
            val sanitizedData = data.replace("`", "\\`")
            webView.evaluateJavascript(
                "if(window.$functionName){window.$functionName(`$sanitizedData`);}",
                null
            )
        }
    }

    @JavascriptInterface
    fun disconnectUsb() {
        serialManager.disconnect()
        sendToWeb("onUsbStatus", "DISCONNECTED")
    }

    @JavascriptInterface
    fun printRawJSON(jsonData: String) = printThermal(jsonData) // alias kept for backwards compat
}