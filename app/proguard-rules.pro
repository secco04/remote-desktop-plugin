# This release build type had isMinifyEnabled=true with NO proguardFiles() at all (fixed
# alongside this file) — meaning R8 ran with none of AGP's own default Android rules, a
# pre-existing gap unrelated to any one feature. The keep rules below protect two things R8
# has no way to see are actually reachable:
#
# 1. Classes/methods invoked purely via JNI reflection from native code (FindClass +
#    GetStaticMethodID/GetMethodID by exact name+signature) — there is no Java-level call site
#    for R8's reachability analysis to follow, unlike a real interface/virtual-dispatch call.
#    `native`/`external` methods themselves are already exempt from R8 stripping/renaming by
#    default (it recognizes the modifier), but plain static callback methods looked up this way
#    are NOT automatically protected, and neither are the manifest-referenced Service/Activity
#    classes without AGP's own default rules being applied.

# Manifest-referenced components — obfuscating/removing these breaks <service>/<activity> lookup.
-keep class de.lobianco.saftssh.remotedesktop.RemoteDesktopSessionService { *; }
-keep class de.lobianco.saftssh.remotedesktop.InfoActivity { *; }

# SPICE: libspice.so's JNI_OnLoad resolves OnSettingsChanged/OnGraphicsUpdate/OnMouseUpdate/
# OnMouseMode/ShowMessage/OnRemoteClipboardChanged via GetStaticMethodID against this exact class
# (see SpiceCommunicator.kt's class doc) — none of these calls are visible to R8.
-keep class com.undatech.opaque.SpiceCommunicator { *; }
-keep class com.undatech.opaque.SpiceCommunicator$Listener { *; }

# GStreamer's own vendored init glue — libgstreamer_android.so resolves nativeInit the same way.
-keep class org.freedesktop.gstreamer.GStreamer { *; }

# RDP: FreeRDP's vendored JNI bridge — its native methods are already exempt, but the UIEventListener/
# EventListener interfaces are implemented via anonymous objects in RdpClient.kt; keeping the whole
# class defensively avoids relying on R8 correctly tracing that interface dispatch through to the
# native side's own interface method lookups (LibFreeRDP.java's own JNI layer may look some of these
# up by name+signature too, same risk category as the two classes above).
-keep class com.freerdp.freerdpcore.services.LibFreeRDP { *; }
-keep class com.freerdp.freerdpcore.services.LibFreeRDP$* { *; }
