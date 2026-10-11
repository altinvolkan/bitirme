# gomobile (Go -> Java köprüsü) yansıma/JNI ile erişilir; küçültme bunları silmemeli
-keep class go.** { *; }
-keep class sender.** { *; }
-keep class com.example.screenmirror.airplay.** { *; }
# AirPlayBackend Class.forName ile yüklenir
-keep class com.example.screenmirror.MirrorBackend { *; }
-keep class * implements com.example.screenmirror.MirrorBackend { *; }
-dontwarn go.**
