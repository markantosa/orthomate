package com.epd3dg6.bleapp.data

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class DeviceData(
    val fsr1: Int = 0,      // 0-100 snapshot/measured %
    val fsr2: Int = 0,
    val fsr3: Int = 0,
    val live1: Int = 0,     // 0-100 live FSR readings
    val live2: Int = 0,
    val live3: Int = 0,
    val mode: String = "—",
    val snap: Int = 0,      // 0 or 1
    val act1: Int = 0,      // actuator 1 position steps
    val act2: Int = 0,
    val act3: Int = 0
)

data class FsrSnapshot(
    val fsr1: Int,
    val fsr2: Int,
    val fsr3: Int,
    val mode: String,
    val timestamp: Long
)
