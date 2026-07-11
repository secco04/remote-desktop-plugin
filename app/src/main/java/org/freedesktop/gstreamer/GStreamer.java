/*
 * Vendored verbatim from GStreamer's own public Android-integration boilerplate
 * ("Copy this file into your Android project and call init()" — the file's own header
 * below, unchanged). LGPL-2.1, distribution-permissive by design: this exact glue class
 * is meant to be copied into third-party Android apps that bundle GStreamer's prebuilt
 * Android binaries. See this repo's LICENSE for full attribution.
 *
 * Required by SPICE support: libspice.so calls org.freedesktop.gstreamer.GStreamer.init()
 * (via SpiceCommunicator's static initializer, see that class) before any SPICE session —
 * spice-gtk uses GStreamer internally for its video-streaming channel decode even when
 * audio is disabled. Package/class name and the native nativeInit() declaration are
 * unchanged from upstream because libgstreamer_android.so's JNI entry point is compiled
 * against this exact class (same reasoning as LibFreeRDP.java's unchanged package name).
 *
 * copyFonts()/copyCaCertificates() read from this app's assets/fontconfig/ and
 * assets/ssl/certs/ — see this plugin's assets/ directory (extracted from the same
 * freeaSPICE release APK the native libraries came from).
 */

/**
 * Copy this file into your Android project and call init(). If your project
 * contains fonts and/or certificates in assets, uncomment copyFonts() and/or
 * copyCaCertificates() lines in init().
 */
package org.freedesktop.gstreamer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.content.Context;
import android.content.res.AssetManager;

public class GStreamer {
    private static native void nativeInit(Context context) throws Exception;

    public static void init(Context context) throws Exception {
        copyFonts(context);
        copyCaCertificates(context);
        nativeInit(context);
    }

    private static void copyFonts(Context context) {
        AssetManager assetManager = context.getAssets();
        File filesDir = context.getFilesDir();
        File fontsFCDir = new File (filesDir, "fontconfig");
        File fontsDir = new File (fontsFCDir, "fonts");
        File fontsCfg = new File (fontsFCDir, "fonts.conf");

        fontsDir.mkdirs();

        try {
            /* Copy the config file */
            copyFile (assetManager, "fontconfig/fonts.conf", fontsCfg);
            /* Copy the fonts */
            for(String filename : assetManager.list("fontconfig/fonts/truetype")) {
                File font = new File(fontsDir, filename);
                copyFile (assetManager, "fontconfig/fonts/truetype/" + filename, font);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void copyCaCertificates(Context context) {
        AssetManager assetManager = context.getAssets();
        File filesDir = context.getFilesDir();
        File sslDir = new File (filesDir, "ssl");
        File certsDir = new File (sslDir, "certs");
        File certs = new File (certsDir, "ca-certificates.crt");

        certsDir.mkdirs();

        try {
            /* Copy the certificates file */
            copyFile (assetManager, "ssl/certs/ca-certificates.crt", certs);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void copyFile(AssetManager assetManager, String assetPath, File outFile) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        IOException exception = null;

        if (outFile.exists())
            outFile.delete();

        try {
            in = assetManager.open(assetPath);
            out = new FileOutputStream(outFile);

            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException e) {
            exception = e;
        } finally {
            if (in != null)
                try {
                    in.close();
                } catch (IOException e) {
                    if (exception == null)
                        exception = e;
                }
            if (out != null)
                try {
                    out.close();
                } catch (IOException e) {
                    if (exception == null)
                        exception = e;
                }
            if (exception != null)
                throw exception;
        }
    }
}
