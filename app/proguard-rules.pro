# General Android
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep Entry Points (Service)
-keep class com.gameautoeditor.player.AutomationService { *; }
-keep class com.gameautoeditor.player.MainActivity { *; }

# OpenCV
-keep class org.opencv.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# Allow Obfuscation for our Logic Classes
# PerceptionSystem, SceneGraphEngine, ActionSystem will be renamed to 'a', 'b', 'c'
