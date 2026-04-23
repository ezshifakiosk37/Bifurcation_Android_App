package com.example.bifurcation_app_android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.OutputStream
import java.util.*

class BluetoothPrinterManager(private val context: Context) {
    private val sharedPrefs = context.getSharedPreferences("PrinterPrefs", Context.MODE_PRIVATE)
    private val UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun getPairedPrinters(): List<BluetoothDevice> {
        return BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
    }

    fun savePrinter(address: String) {
        sharedPrefs.edit().putString("last_printer_mac", address).apply()
    }

    fun getSavedPrinterAddress(): String? {
        return sharedPrefs.getString("last_printer_mac", null)
    }

    /**
     * Lightweight connection test — opens RFCOMM socket, sends ESC/@ init only,
     * then closes. Does NOT print anything. Used by selectPrinter() in the bridge.
     *
     * Returns true  → socket opened successfully (printer is reachable & on)
     * Returns false → socket failed (printer off / out of range / wrong MAC)
     */
    @SuppressLint("MissingPermission")
    fun testConnection(address: String): Boolean {
        return try {
            val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
            val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(UUID_SPP)
            socket.connect()
            // Just init — proves the link is alive
            socket.outputStream.write(byteArrayOf(0x1B, 0x40))
            socket.outputStream.flush()
            socket.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Prints plain ESC/POS text to the last saved printer MAC.
     */
    @SuppressLint("MissingPermission")
    fun connectAndPrint(data: String): Boolean {
        val address = sharedPrefs.getString("last_printer_mac", null) ?: return false
        return try {
            val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
            val socket = device.createRfcommSocketToServiceRecord(UUID_SPP)
            socket.connect()
            val out = socket.outputStream

            out.write(byteArrayOf(0x1B, 0x40)) // Init
            out.write(data.toByteArray())
            out.write(byteArrayOf(0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00)) // Feed / Cut

            out.flush()
            socket.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Prints a Bitmap (PNG/JPG) using ESC/POS GS v 0 raster command.
     */
    @SuppressLint("MissingPermission")
    fun connectAndPrintBitmap(bitmap: Bitmap): Boolean {
        val address = sharedPrefs.getString("last_printer_mac", null) ?: return false
        return try {
            val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
            val socket = device.createRfcommSocketToServiceRecord(UUID_SPP)
            socket.connect()
            val out = socket.outputStream

            out.write(byteArrayOf(0x1B, 0x40))
            out.write(decodeBitmap(bitmap))
            out.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00))

            out.flush()
            socket.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Converts an Android Bitmap to ESC/POS "GS v 0" raster image command bytes.
     */
    private fun decodeBitmap(bmp: Bitmap): ByteArray {
        val width = (bmp.width + 7) / 8 * 8
        val height = bmp.height
        val dataWidth = width / 8
        val payload = ByteArray(dataWidth * height)

        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width step 8) {
                var byte = 0
                for (bit in 0 until 8) {
                    val px = x + bit
                    if (px < bmp.width) {
                        val pixel = bmp.getPixel(px, y)
                        val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                        if (gray < 128) {
                            byte = byte or (1 shl (7 - bit))
                        }
                    }
                }
                payload[index++] = byte.toByte()
            }
        }

        val header = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (dataWidth % 256).toByte(), (dataWidth / 256).toByte(),
            (height % 256).toByte(), (height / 256).toByte()
        )

        return header + payload
    }
}