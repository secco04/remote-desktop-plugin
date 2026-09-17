/*
   Android FreeRDP JNI Wrapper

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.

   ---
   LobiShell modifications (this file, MPL-2.0 — see header above; the rest of this
   plugin is GPL-3.0, see this repo's LICENSE, MPL/GPL combination is expressly permitted
   by MPL 2.0 section 3.3):

   As of 2026-09, the native libraries backing this were built from FreeRDP's own
   upstream source at tag 3.31.1 (client/Android/Studio/freeRDPCore's own CMake
   project, built standalone under WSL rather than through Gradle) instead of the
   previously-vendored prebuilt 2.11.7 binaries — done specifically to pick up
   real RDP cursor-shape support (upstream PR #12786's OnPointerSet/
   OnPointerSetNull/OnPointerSetDefault callbacks, added below), which the old
   2.11.7-era build predates. This file's structure (class doc, native method
   list, listener interfaces) is still modeled on FreeRDP's own upstream JNI
   bridge for that tag:
   https://github.com/FreeRDP/FreeRDP/blob/3.31.1/client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java
   NOT from the bVNC fork — this is FreeRDP's own JNI bridge, license-clean and
   independent of bVNC's GPL-3.0 codebase. Package/class name is UNCHANGED
   (com.freerdp.freerdpcore.services.LibFreeRDP) because the native library
   resolves its native methods against this exact class/package via standard JNI
   symbol-name matching (Java_com_freerdp_freerdpcore_services_LibFreeRDP_...) —
   renaming or moving this class would break that binding.

   Changes from upstream:
   - Removed the BookmarkBase-based setConnectionInfo() overload and its BookmarkBase/
     ManualBookmark imports — that class belongs to FreeRDP's own bookmark-management
     UI layer, which this plugin doesn't use; only the simpler Uri-based overload is
     kept, unchanged.
   - Removed the ApplicationSettingsActivity dependency (only used for a client
     hostname string) — replaced with a plain constant.
   - Replaced the GlobalApp/SessionState-based callback dispatch (which required
     vendoring FreeRDP's whole app-singleton/session-registry layer) with a simple
     per-instance UIEventListener map this plugin owns directly
     (setUIEventListener/removeUIEventListener below) — same information, no extra
     vendored classes.
   - freerdp_get_build_date() removed: no longer exported by the 3.31.1 native
     library (confirmed via `nm`/symbol search against the freshly built
     libfreerdp-android.so) — keeping the declaration would risk an
     UnsatisfiedLinkError the first time anything called it.
   - OnVerifyCertificate/OnVerifyChangedCertificate replaced by upstream's own
     3.x OnVerifyCertificateEx/OnVerifyChangedCertificateEx (host/port/flags
     replace the old bare hostMismatch boolean — see VERIFY_CERT_FLAG_* in
     freerdp/freerdp.h; VERIFY_CERT_FLAG_MISMATCH is the old boolean's successor).
   - OnPointerSet/OnPointerSetNull/OnPointerSetDefault are NEW (upstream 3.x,
     PR #12786) — the actual cursor-shape feature this rebuild exists for.
   - The static initializer now ALSO explicitly System.loadLibrary()s
     "freerdp-client3"/"freerdp3"/"winpr3" after "freerdp-android" — copied
     verbatim (down to the comment) from upstream 3.31.1's own static
     initializer, which does this specifically "to trigger JNI_OnLoad calls".
     Android's dynamic linker resolves freerdp-android.so's transitive
     DT_NEEDED deps on its own, but the JVM only invokes JNI_OnLoad for a
     library named directly in System.loadLibrary() — never for one pulled in
     transitively. winpr3.so's own JNI_OnLoad (winpr/libwinpr/utils/android.c)
     is what populates its module-global JavaVM* used by the Android-specific
     JNI-based Unicode conversion path (winpr/libwinpr/crt/unicode_android.c);
     without it that pointer stays NULL and the first UTF8->WChar conversion
     during freerdp_settings_new() segfaults on a null-pointer JNI call
     (WINPR_ASSERT is a no-op in a Release build, so nothing catches it
     earlier) — confirmed by symbolicating a real device tombstone with
     llvm-addr2line against these exact unstripped .so files: the crash
     chain was freerdp_settings_new -> helpers.c's init_app_details ->
     MultiByteToWideChar -> winpr_jni_attach_thread's `(*jniVm)->GetEnv(...)`
     on a null jniVm. This is a real difference from FreeRDP 2.11.7, which
     had no such Android-specific JNI Unicode path and never needed this.
   Every native method declaration and both listener interfaces (including the
   exact method names/signatures native code calls back into) must match exactly
   what the native library expects/provides.
*/

package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.util.LongSparseArray;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LibFreeRDP
{
	private static final String TAG = "LibFreeRDP";
	private static final String CLIENT_NAME = "LobiShell";
	private static EventListener listener;
	private static boolean mHasH264 = false;

	private static final LongSparseArray<Boolean> mInstanceState = new LongSparseArray<>();
	private static final LongSparseArray<UIEventListener> mUiListeners = new LongSparseArray<>();

	static
	{
		try
		{
			System.loadLibrary("freerdp-android");

			/* Load dependent libraries too to trigger JNI_OnLoad calls — see class doc. */
			String version = freerdp_get_jni_version();
			String[] versions = version.split("[\\.-]");
			if (versions.length > 0)
			{
				System.loadLibrary("freerdp-client" + versions[0]);
				System.loadLibrary("freerdp" + versions[0]);
				System.loadLibrary("winpr" + versions[0]);
			}
			Pattern pattern = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*");
			Matcher matcher = pattern.matcher(version);
			if (!matcher.matches() || (matcher.groupCount() < 3))
				throw new RuntimeException("APK broken: native library version " + version +
				                           " does not meet requirements!");
			int major = Integer.parseInt(Objects.requireNonNull(matcher.group(1)));
			int minor = Integer.parseInt(Objects.requireNonNull(matcher.group(2)));
			int patch = Integer.parseInt(Objects.requireNonNull(matcher.group(3)));

			if (major > 2)
				mHasH264 = freerdp_has_h264();
			else if (minor > 5)
				mHasH264 = freerdp_has_h264();
			else if ((minor == 5) && (patch >= 1))
				mHasH264 = freerdp_has_h264();
			else
				throw new RuntimeException("APK broken: native library version " + version +
				                           " does not meet requirements!");
			Log.i(TAG, "Successfully loaded native library. H264 is " +
			               (mHasH264 ? "supported" : "not available"));
		}
		catch (UnsatisfiedLinkError e)
		{
			Log.e(TAG, "Failed to load library: " + e.toString());
			throw e;
		}
	}

	public static boolean hasH264Support()
	{
		return mHasH264;
	}

	private static native boolean freerdp_has_h264();

	private static native String freerdp_get_jni_version();

	private static native String freerdp_get_version();

	private static native String freerdp_get_build_revision();

	private static native String freerdp_get_build_config();

	private static native long freerdp_new(Context context);

	private static native void freerdp_free(long inst);

	private static native boolean freerdp_parse_arguments(long inst, String[] args);

	private static native boolean freerdp_connect(long inst);

	private static native boolean freerdp_disconnect(long inst);

	private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y,
	                                                      int width, int height);

	private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);

	private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);

	private static native boolean freerdp_send_unicodekey_event(long inst, int keycode,
	                                                            boolean down);

	private static native boolean freerdp_is_unicode_input_supported(long inst);

	private static native boolean freerdp_send_clipboard_data(long inst, String data);

	private static native String freerdp_get_last_error_string(long inst);

	/** Public wrapper — FreeRDP's own human-readable reason for the last connect/disconnect
	 *  failure (e.g. distinguishes an NLA/CredSSP negotiation failure from a bad password from a
	 *  network timeout), previously declared but never called: every failure surfaced to the user
	 *  as the same generic "Connection failed" regardless of cause. */
	public static String getLastErrorString(long inst)
	{
		try
		{
			return freerdp_get_last_error_string(inst);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	public static void setEventListener(EventListener l)
	{
		listener = l;
	}

	/** Registers the [UIEventListener] for a given instance — replaces upstream's
	 *  GlobalApp/SessionState session registry (see class doc). Must be called before
	 *  connect() so the OnXxx callbacks below have somewhere to dispatch to. */
	public static void setUIEventListener(long inst, UIEventListener l)
	{
		synchronized (mUiListeners)
		{
			mUiListeners.put(inst, l);
		}
	}

	/** Call once the session is torn down to release the listener reference. */
	public static void removeUIEventListener(long inst)
	{
		synchronized (mUiListeners)
		{
			mUiListeners.remove(inst);
		}
	}

	private static UIEventListener uiListenerFor(long inst)
	{
		synchronized (mUiListeners)
		{
			return mUiListeners.get(inst);
		}
	}

	public static long newInstance(Context context)
	{
		return freerdp_new(context);
	}

	public static void freeInstance(long inst)
	{
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				freerdp_disconnect(inst);
			}
			while (mInstanceState.get(inst, false))
			{
				try
				{
					mInstanceState.wait();
				}
				catch (InterruptedException e)
				{
					throw new RuntimeException();
				}
			}
		}
		freerdp_free(inst);
	}

	public static boolean connect(long inst)
	{
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				throw new RuntimeException("instance already connected");
			}
		}
		return freerdp_connect(inst);
	}

	public static boolean disconnect(long inst)
	{
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				return freerdp_disconnect(inst);
			}
			return true;
		}
	}

	public static boolean cancelConnection(long inst)
	{
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				return freerdp_disconnect(inst);
			}
			return true;
		}
	}

	public static boolean setConnectionInfo(Context context, long inst, Uri openUri)
	{
		ArrayList<String> args = new ArrayList<>();

		// Parse URI from query string. Same key overwrite previous one
		// freerdp://user@ip:port/connect?sound=&rfx=&p=password&clipboard=%2b&themes=-

		// Now we only support Software GDI
		args.add(TAG);
		args.add("/gdi:sw");

		if (!CLIENT_NAME.isEmpty())
		{
			args.add("/client-hostname:" + CLIENT_NAME);
		}

		// Parse hostname and port. Set to 'v' argument
		String hostname = openUri.getHost();
		int port = openUri.getPort();
		if (hostname != null)
		{
			hostname = hostname + ((port == -1) ? "" : (":" + String.valueOf(port)));
			args.add("/v:" + hostname);
		}

		String user = openUri.getUserInfo();
		if (user != null)
		{
			args.add("/u:" + user);
		}

		for (String key : openUri.getQueryParameterNames())
		{
			String value = openUri.getQueryParameter(key);

			if (value.isEmpty())
			{
				// Query: key=
				// To freerdp argument: /key
				args.add("/" + key);
			}
			else if (value.equals("-") || value.equals("+"))
			{
				// Query: key=- or key=+
				// To freerdp argument: -key or +key
				args.add(value + key);
			}
			else
			{
				// Query: key=value
				// To freerdp argument: /key:value
				if (key.equals("drive") && value.equals("sdcard"))
				{
					// Special for sdcard redirect
					String path = android.os.Environment.getExternalStorageDirectory().getPath();
					value = "sdcard," + path;
				}

				args.add("/" + key + ":" + value);
			}
		}

		String[] arrayArgs = args.toArray(new String[args.size()]);
		return freerdp_parse_arguments(inst, arrayArgs);
	}

	public static boolean updateGraphics(long inst, Bitmap bitmap, int x, int y, int width,
	                                     int height)
	{
		return freerdp_update_graphics(inst, bitmap, x, y, width, height);
	}

	public static boolean sendCursorEvent(long inst, int x, int y, int flags)
	{
		return freerdp_send_cursor_event(inst, x, y, flags);
	}

	public static boolean sendKeyEvent(long inst, int keycode, boolean down)
	{
		return freerdp_send_key_event(inst, keycode, down);
	}

	public static boolean sendUnicodeKeyEvent(long inst, int keycode, boolean down)
	{
		return freerdp_send_unicodekey_event(inst, keycode, down);
	}

	/**
	 * True if the connected server accepted Unicode keyboard input during capability negotiation.
	 * MUST be checked before ever calling sendUnicodeKeyEvent(): the vendored native
	 * android_event.c (android_process_event's EVENT_TYPE_KEY_UNICODE case) treats
	 * freerdp_input_send_unicode_keyboard_event()'s normal, documented FALSE return for an
	 * unsupported server as a fatal I/O failure and tears down the ENTIRE connection — confirmed by
	 * symbolicating a real device tombstone (chain: freerdp_input_send_unicode_keyboard_event
	 * returns FALSE -> android_process_event returns FALSE -> android_check_handle returns FALSE ->
	 * android_freerdp_run's main loop logs "Failed to check android file descriptor" and breaks).
	 * A genuine upstream bug (not ours to fix without patching/rebuilding the native library), so
	 * RdpClient avoids it entirely by using this check to route printable characters through the
	 * Virtual-Key path instead whenever it returns false. See RdpClient's [unicodeSupported] doc.
	 */
	public static boolean isUnicodeInputSupported(long inst)
	{
		try
		{
			return freerdp_is_unicode_input_supported(inst);
		}
		catch (Exception e)
		{
			return true; // optimistic default — matches every server that DOES support it
		}
	}

	public static boolean sendClipboardData(long inst, String data)
	{
		return freerdp_send_clipboard_data(inst, data);
	}

	private static void OnConnectionSuccess(long inst)
	{
		if (listener != null)
			listener.OnConnectionSuccess(inst);
		synchronized (mInstanceState)
		{
			mInstanceState.append(inst, true);
			mInstanceState.notifyAll();
		}
	}

	private static void OnConnectionFailure(long inst)
	{
		if (listener != null)
			listener.OnConnectionFailure(inst);
		synchronized (mInstanceState)
		{
			mInstanceState.remove(inst);
			mInstanceState.notifyAll();
		}
	}

	private static void OnPreConnect(long inst)
	{
		if (listener != null)
			listener.OnPreConnect(inst);
	}

	private static void OnDisconnecting(long inst)
	{
		if (listener != null)
			listener.OnDisconnecting(inst);
	}

	private static void OnDisconnected(long inst)
	{
		if (listener != null)
			listener.OnDisconnected(inst);
		synchronized (mInstanceState)
		{
			mInstanceState.remove(inst);
			mInstanceState.notifyAll();
		}
	}

	private static void OnSettingsChanged(long inst, int width, int height, int bpp)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			uiEventListener.OnSettingsChanged(width, height, bpp);
	}

	private static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain,
	                                      StringBuilder password)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			return uiEventListener.OnAuthenticate(username, domain, password);
		return false;
	}

	private static boolean OnGatewayAuthenticate(long inst, StringBuilder username,
	                                             StringBuilder domain, StringBuilder password)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			return uiEventListener.OnGatewayAuthenticate(username, domain, password);
		return false;
	}

	private static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
	                                         String subject, String issuer, String fingerprint,
	                                         long flags)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			return uiEventListener.OnVerifyCertificateEx(host, port, commonName, subject, issuer,
			                                             fingerprint, flags);
		return 0;
	}

	private static int OnVerifyChangedCertificateEx(long inst, String host, long port,
	                                                String commonName, String subject,
	                                                String issuer, String fingerprint,
	                                                String oldSubject, String oldIssuer,
	                                                String oldFingerprint, long flags)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			return uiEventListener.OnVerifyChangedCertificateEx(
			    host, port, commonName, subject, issuer, fingerprint, oldSubject, oldIssuer,
			    oldFingerprint, flags);
		return 0;
	}

	/** NEW in FreeRDP 3.x (upstream PR #12786) — the actual cursor-shape callbacks. [pixels] is a
	 *  premultiplied ARGB pixel buffer of size width*height (row-major), [xPos]/[yPos] the cursor's
	 *  hotspot within that bitmap. Fires whenever the remote sets a custom cursor shape. */
	private static void OnPointerSet(long inst, int[] pixels, int width, int height, int xPos,
	                                 int yPos)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			uiEventListener.OnPointerSet(pixels, width, height, xPos, yPos);
	}

	/** Remote hid the cursor entirely (e.g. app hover-hides it). */
	private static void OnPointerSetNull(long inst)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			uiEventListener.OnPointerSetNull();
	}

	/** Remote wants the platform's default arrow cursor back. */
	private static void OnPointerSetDefault(long inst)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			uiEventListener.OnPointerSetDefault();
	}

	private static void OnGraphicsUpdate(long inst, int x, int y, int width, int height)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			uiEventListener.OnGraphicsUpdate(x, y, width, height);
	}

	private static void OnGraphicsResize(long inst, int width, int height, int bpp)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			uiEventListener.OnGraphicsResize(width, height, bpp);
	}

	private static void OnRemoteClipboardChanged(long inst, String data)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			uiEventListener.OnRemoteClipboardChanged(data);
	}

	public static String getVersion()
	{
		return freerdp_get_version();
	}

	public static interface EventListener {
		void OnPreConnect(long instance);

		void OnConnectionSuccess(long instance);

		void OnConnectionFailure(long instance);

		void OnDisconnecting(long instance);

		void OnDisconnected(long instance);
	}

	public static interface UIEventListener {
		void OnSettingsChanged(int width, int height, int bpp);

		boolean OnAuthenticate(StringBuilder username, StringBuilder domain,
		                       StringBuilder password);

		boolean OnGatewayAuthenticate(StringBuilder username, StringBuilder domain,
		                              StringBuilder password);

		int OnVerifyCertificateEx(String host, long port, String commonName, String subject,
		                         String issuer, String fingerprint, long flags);

		int OnVerifyChangedCertificateEx(String host, long port, String commonName, String subject,
		                                 String issuer, String fingerprint, String oldSubject,
		                                 String oldIssuer, String oldFingerprint, long flags);

		void OnGraphicsUpdate(int x, int y, int width, int height);

		void OnGraphicsResize(int width, int height, int bpp);

		void OnRemoteClipboardChanged(String data);

		/** See LibFreeRDP.OnPointerSet's doc — real cursor-shape support (FreeRDP 3.x only). */
		void OnPointerSet(int[] pixels, int width, int height, int xPos, int yPos);

		void OnPointerSetNull();

		void OnPointerSetDefault();
	}
}
