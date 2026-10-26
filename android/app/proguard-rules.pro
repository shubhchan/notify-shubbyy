# Proguard rules for Eclipse Paho MQTT
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# Keep App components & bindings
-keep class com.shubbyy.notify.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-dontwarn javax.naming.**
