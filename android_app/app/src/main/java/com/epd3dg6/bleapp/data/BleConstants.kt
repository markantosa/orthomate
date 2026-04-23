package com.epd3dg6.bleapp.data

import java.util.UUID

// All UUIDs and command bytes
object BleConstants {
    const val DEVICE_NAME = "EPD3DG6"
    val SVC_UUID: UUID = UUID.fromString("4fa0c560-78a3-11ee-b962-0242ac120002")
    val CHAR_UUID: UUID = UUID.fromString("4fa0c561-78a3-11ee-b962-0242ac120002")
    val CMD_UUID: UUID = UUID.fromString("4fa0c562-78a3-11ee-b962-0242ac120002")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_BUTTON: Byte = 0x01
    const val CMD_HOME: Byte = 0x02
    const val CMD_ESTOP: Byte = 0x03
}
