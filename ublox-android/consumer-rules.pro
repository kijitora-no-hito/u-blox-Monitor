# ublox-android を使うアプリの R8 用。usb-serial-for-android のドライバはリフレクションで作られる（ProbeTable）
-keep class com.hoho.android.usbserial.driver.* { *; }
