# LiteRT / TensorFlow Lite keeps reflective entry points and GPU/NNAPI delegate classes.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**

# Play Services location
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**
