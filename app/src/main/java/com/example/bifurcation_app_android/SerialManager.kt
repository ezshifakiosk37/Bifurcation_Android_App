package com.example.bifurcation_app_android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

class SerialManager(private val context: Context) : SerialInputOutputManager.Listener {
    private val TAG = "USB_SERIAL_DEBUG"
    private val ACTION_USB_PERMISSION = "com.example.bifurcation_app_android.USB_PERMISSION"

    private var usbSerialPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private var currentCallback: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Accumulates incoming 1-byte "shards" into complete messages
    private val dataBuffer = StringBuilder()

    init {
        Log.i(TAG, "SerialManager initialized.")
    }
    /**
     * 2. Helper to show Toast on the UI Thread
     */
    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * INBOUND DATA LOGIC
     * Handles fragmentation by searching for terminators (P, X, \n)
     */
    override fun onNewData(data: ByteArray) {
        dataBuffer.append(String(data, Charsets.UTF_8))

        // Valid terminators for standard sensors
        val terminators = charArrayOf('P', 'X', 'S', '\n', '\r')

        // Logic: Keep processing as long as there's something to process
        while (true) {
            var endOfPacketIndex = dataBuffer.indexOfAny(terminators)

            // If no standard terminator, check for the BP pattern specifically
            if (endOfPacketIndex == -1) {
                val bpRegex = Regex("B:\\d{2,3}:\\d{2,3}")
                val bpMatch = bpRegex.find(dataBuffer)
                if (bpMatch != null) {
                    // We found a complete BP packet. We treat its end as the terminator.
                    endOfPacketIndex = bpMatch.range.last
                }
            }

            // If we still have no terminator, stop and wait for more data
            if (endOfPacketIndex == -1) break

            try {
                // Slice the message
                val fullMessage = dataBuffer.substring(0, endOfPacketIndex + 1).trim()
                dataBuffer.delete(0, endOfPacketIndex + 1)

                if (fullMessage.isNotEmpty()) {
                    Log.i(TAG, "ESP32: $fullMessage")
                    mainHandler.post { currentCallback?.invoke(fullMessage) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Buffer Slice Error: ${e.message}")
                dataBuffer.setLength(0) // Nuclear option: clear buffer to stop crash loop
                break
            }
        }
    }

    /**
     * OUTBOUND DATA LOGIC
     * Sends a string to the ESP32. We append '\n' so the ESP32
     * knows the message is complete on its end.
     */
    fun sendData(data: String): Boolean {
        val port = usbSerialPort
        if (port == null || !port.isOpen) {
            Log.e(TAG, "Write failed: Port is null or closed.")
            return false
        }

        return try {
            // Append newline as a terminator for ESP32's readStringUntil('\n')
            val payload = (data + "\n").toByteArray()
            port.write(payload, 2000) // 2000ms timeout
            Log.i(TAG, "Android: $data")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Write error: ${e.message}")
            false
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial loop stopped: ${e.message}")
        showToast("Hardware Disconnected")
        disconnect()
    }

    fun connect(onDataReceived: (String) -> Unit): String {
        if (usbSerialPort != null && usbSerialPort!!.isOpen) return "ALREADY_CONNECTED"

        currentCallback = onDataReceived
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            showToast("No USB device found") // 4. Feedback for no device
            return "DEVICE_NOT_FOUND"
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!manager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(device, permissionIntent)
            return "PERMISSION_REQUESTED"
        }

        val connection = manager.openDevice(device) ?: return "OPEN_FAILED"

        return try {
            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // DTR/RTS must be true for most ESP32/CP210x chips to communicate
            port.dtr = true
            port.rts = true

            usbSerialPort = port
            usbIoManager = SerialInputOutputManager(usbSerialPort, this)
            usbIoManager?.start()

            Log.i(TAG, "Connection Successful.")
            showToast("Hardware Connected Successfully")
            "CONNECTED"
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.localizedMessage}")
            showToast("Connection Failed: ${e.localizedMessage}")
            "ERROR"
        }
    }

    fun disconnect() {
        Log.i(TAG, "Disconnecting...")
        usbIoManager?.apply {
            setListener(null)
            stop()
        }
        usbIoManager = null
        dataBuffer.setLength(0)

        try {
            usbSerialPort?.apply {
                dtr = false
                rts = false
                close()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Close error: ${e.message}")
        }
        usbSerialPort = null
    }

    private fun StringBuilder.indexOfAny(chars: CharArray): Int {
        for (i in this.indices) {
            if (this[i] in chars) return i
        }
        return -1
    }
}