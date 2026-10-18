# Keyboard assets are read reflectively by name from AssetManager - keep nothing special,
# but make sure R8 does not strip the InputMethodService subclass referenced from the manifest.
-keep class com.ioskeyboard.app.ime.KeyboardIME { *; }
-keep class com.ioskeyboard.app.floatwin.** { *; }
-keepclassmembers class com.ioskeyboard.app.data.ClipItem { *; }
