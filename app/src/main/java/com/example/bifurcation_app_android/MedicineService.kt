package com.example.bifurcation_app_android

import android.util.Log
import org.json.JSONObject

class MedicineService(private val serialManager: SerialManager) {

    private val TAG = "USB_SERIAL_DEBUG"

    fun processCommand(rawJson: String) {
        if (rawJson.isBlank()) {
            Log.e(TAG, "REJECTED: Empty payload.")
            return
        }

        try {
            val json = JSONObject(rawJson)
            val action = json.optString("action", "unknown").lowercase()

            when (action) {
                "dispense" -> {
                    val row = json.optInt("row", -1)
                    val col = json.optInt("col", -1)
                    val qty = json.optInt("quantity", 0)

                    if (row < 0 || col < 0 || qty <= 0) {
                        Log.e(TAG, "VALIDATION FAILED: Invalid R/C/Q values.")
                        return
                    }

                    // TRANSLATION LAYER: Convert JSON to "D:R1,C2,Q5"
                    val customString = "D:R$row,C$col,Q$qty"
                    transmitToHardware(customString)
                }

                "status" -> {
                    transmitToHardware("S:CHECK")
                }

                else -> Log.e(TAG, "UNKNOWN ACTION: $action")
            }

        } catch (e: Exception) {
            Log.e(TAG, "JSON ERROR: ${e.message}")
        }
    }

    private fun transmitToHardware(payload: String) {
        // STILL CRITICAL: The \n tells the ESP32 the message is finished
        val finalPayload = "$payload\n"

//        Log.i(TAG, "TRANSMITTING STRING: $finalPayload")

        val success = serialManager.sendData(finalPayload)

        if (success) {
            Log.d(TAG, "Android: Data sent.")
        } else {
            Log.e(TAG, "Android: Serial unreachable.")
        }
    }
}