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

   Vendored from FreeRDP's own upstream Android client at tag 2.11.7 (NOT master —
   the prebuilt native libraries bundled in this plugin were built from that exact
   tag per iiordanov/remote-desktop-clients' own build-deps.conf, and FreeRDP's
   Android JNI surface has changed since; an earlier version of this file was
   mistakenly vendored from current master and crashed at runtime with
   "Failed to register native method ... freerdp_get_build_date" because the
   native library's JNI_OnLoad batch-registers ALL its native methods against this
   class in one call, and no static/native method here matched
   freerdp_get_build_date — RegisterNatives has no partial-success mode, so a
   single unresolvable entry aborts the whole batch):
   https://github.com/FreeRDP/FreeRDP/blob/2.11.7/client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java
   NOT from the bVNC fork — this is FreeRDP's own JNI bridge, license-clean and
   independent of bVNC's GPL-3.0 codebase (verified none of bVNC's ~20 build
   patches touch this file's native method declarations or the OnXxx callback
   methods native code invokes by name — they only touch the BookmarkBase-based
   setConnectionInfo() path this file doesn't even keep). Package/class name is
   UNCHANGED (com.freerdp.freerdpcore.services.LibFreeRDP) because the native
   library's JNI_OnLoad registers its native methods against this exact class via
   RegisterNatives() — renaming or moving this class would break that binding
   (confirmed by inspecting the compiled classes.dex of a real freeaRDP release
   APK, which reports this exact package/class, not a guess).

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
   Every native method declaration and both listener interfaces (including the
   exact method names/signatures native code calls back into, like the misspelled
   "OnVerifiyCertificate") are UNCHANGED from upstream 2.11.7, since those must
   match exactly what the native library expects/provides.
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
			String version = freerdp_get_jni_version();
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

	private static native String freerdp_get_build_date();

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

	private static native boolean freerdp_send_clipboard_data(long inst, String data);

	private static native String freerdp_get_last_error_string(long inst);

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

	private static int OnVerifyCertificate(long inst, String commonName, String subject,
	                                       String issuer, String fingerprint, boolean hostMismatch)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			return uiEventListener.OnVerifiyCertificate(commonName, subject, issuer, fingerprint,
			                                            hostMismatch);
		return 0;
	}

	private static int OnVerifyChangedCertificate(long inst, String commonName, String subject,
	                                              String issuer, String fingerprint,
	                                              String oldSubject, String oldIssuer,
	                                              String oldFingerprint)
	{
		UIEventListener uiEventListener = uiListenerFor(inst);
		if (uiEventListener != null)
			return uiEventListener.OnVerifyChangedCertificate(
			    commonName, subject, issuer, fingerprint, oldSubject, oldIssuer, oldFingerprint);
		return 0;
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

		int OnVerifiyCertificate(String commonName, String subject, String issuer,
		                         String fingerprint, boolean mismatch);

		int OnVerifyChangedCertificate(String commonName, String subject, String issuer,
		                               String fingerprint, String oldSubject, String oldIssuer,
		                               String oldFingerprint);

		void OnGraphicsUpdate(int x, int y, int width, int height);

		void OnGraphicsResize(int width, int height, int bpp);

		void OnRemoteClipboardChanged(String data);
	}
}
