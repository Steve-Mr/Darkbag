# Keep source file and line numbers for crash stacktraces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep JNI classes, fields, and methods accessed by native C++ code (native-lib.cpp & HdrPlusJNI.cpp)
-keep class top.maary.darkbag.models.CaptureMetadata {
    *;
}
-keep class top.maary.darkbag.processor.ColorProcessor {
    native <methods>;
}
-keep class top.maary.darkbag.rawvideo.RawVideoNative {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep reflection targets used for PopupMenu icon display
-keepclassmembers class androidx.appcompat.widget.PopupMenu {
    *** mPopup;
}
-keep class androidx.appcompat.view.menu.MenuPopupHelper {
    public void setForceShowIcon(boolean);
}

# Keep Parcelable / @Parcelize CREATORs and Navigation SafeArgs
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * implements androidx.navigation.NavArgs {
    *;
}

# Keep custom View constructors inflated from XML
-keepclasseswithmembers class top.maary.darkbag.ui.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
