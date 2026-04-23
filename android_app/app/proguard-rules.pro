# Proguard rules — keep BLE & JSON classes
-keep class com.epd3dg6.bleapp.** { *; }
-keepclassmembers class * extends android.bluetooth.BluetoothGattCallback { *; }
-keep class org.json.** { *; }
