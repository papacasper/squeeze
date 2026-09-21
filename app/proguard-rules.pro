# youtubedl-android / ffmpeg bundle their own Gson-based config parsing and reflectively
# constructed model classes; without keep rules R8 strips/renames them and init() crashes
# with "class X is not a concrete class" as soon as it reaches that code path.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.yausername.common.** { *; }

-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
