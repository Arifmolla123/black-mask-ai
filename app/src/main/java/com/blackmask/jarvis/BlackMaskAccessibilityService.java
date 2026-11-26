package com.blackmask.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

/**
 * Auto-tap for Black Mask AI: with the accessibility permission the app
 * can press buttons in other apps, so "Wi-Fi on" flips the switch by
 * itself and "take a photo" presses the camera shutter.
 *
 * WhatsApp is deliberately NOT automated: it stays exactly as it is -
 * the user taps Send. (WhatsApp also dislikes automation.)
 *
 * The user turns this on from Settings inside the app. On Android 13+,
 * a sideloaded APK also needs "Allow restricted settings" in App info
 * first, or the accessibility switch stays greyed out.
 */
public class BlackMaskAccessibilityService extends AccessibilityService {

    public static final String ACTION_GLOBAL = "com.blackmask.jarvis.GLOBAL";
    public static final String ACTION_WIFI_ON = "wifi_on";
    public static final String ACTION_WIFI_OFF = "wifi_off";
    public static final String ACTION_CAMERA_SHUTTER = "camera_shutter";
    public static final String ACTION_LOCATION_ON = "location_on";

    private static BlackMaskAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final Handler staticHandler = new Handler(Looper.getMainLooper());
    private static Runnable pendingClearTask;

    // Anti-theft (v4.9.7): power-menu gate + volume-down hold detection.
    private long lastGateTime = 0;
    // App Lock (v4.9.12): PIN gate when a locked app comes to the front.
    private long lastAppLockTime = 0;
    // App Lock (v4.9.14): the last foreground app that is not ours and
    // not SystemUI. The unlock grace lives only while this stays the
    // unlocked app - the moment the user leaves it, the grace ends.
    private String lastPkg = null;
    private long lastChatFgSave = 0;
    private long volDownSince = 0;
    private final Runnable volDownCheck = () -> {
        if (volDownSince == 0) return;
        if (SystemClock.uptimeMillis() - volDownSince < 3000) return;
        volDownSince = 0;
        // Volume down held 3s straight: the thief may be holding
        // power + volume down for the force restart. Fire the alert
        // NOW, before the restart - location SMS + loud ring + lock.
        SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
        if (p.getBoolean("antitheft_on", false)
                && !p.getString("antitheft_pin", "").isEmpty()
                && !p.getString("antitheft_num", "").isEmpty()) {
            CommandEngine.sendAntiTheftAlert(this, "VOL");
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        // Fresh start: any unlock grace from before is gone - the next
        // locked app asks for the PIN again.
        getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                .remove("applock_grace_pkg").remove("applock_grace_until").apply();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        // Anti-theft power-menu gate: the moment the system power menu
        // opens, close it and ask for the PIN instead.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            checkPowerMenu(event);
            // App Lock: remember which app is in front first, so the
            // unlock grace ends the moment the user leaves the app,
            // then ask for the PIN if a locked app just came up.
            trackForeground(event);
            checkAppLock(event);
        }
        String pending = getSharedPreferences("jarvis", MODE_PRIVATE).getString("pending_auto", "");
        if (pending.isEmpty()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (ACTION_WIFI_ON.equals(pending) || ACTION_WIFI_OFF.equals(pending)) {
                boolean wantOn = ACTION_WIFI_ON.equals(pending);
                AccessibilityNodeInfo sw = findSwitchNear(root, "wi-fi");
                if (sw != null) {
                    if (sw.isChecked() != wantOn) {
                        sw.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    clearPending();
                }
            } else if (ACTION_LOCATION_ON.equals(pending)) {
                // Auto location-on (v4.9.10): the system Location settings
                // screen is open - tap its switch if it is off, then go
                // back to whatever was on screen (SOS / the PIN gate).
                AccessibilityNodeInfo sw = findSwitchNear(root, "location");
                if (sw != null) {
                    if (sw.isCheckable() && !sw.isChecked()) {
                        sw.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    clearPending();
                    clearLocPanelFlag();
                    handler.postDelayed(
                            () -> performGlobalAction(GLOBAL_ACTION_BACK), 600);
                }
            } else if (ACTION_CAMERA_SHUTTER.equals(pending)) {
                AccessibilityNodeInfo shutter = findByDescription(root,
                        "shutter", "capture", "take photo", "take picture");
                if (shutter != null) {
                    shutter.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    clearPending();
                } else {
                    // No shutter button found by name - tap where the shutter
                    // lives on almost every camera app (bottom-center).
                    clearPending();
                    handler.postDelayed(this::tapBottomCenter, 1200);
                }
            }
        } finally {
            root.recycle();
        }
    }

    @Override
    public void onInterrupt() {}

    /**
     * Volume keys, observed only - never consumed, so volume control
     * keeps working normally. A 3-second continuous hold is not how
     * anyone lowers the volume, so it means a force-restart attempt.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    volDownSince = SystemClock.uptimeMillis();
                    handler.removeCallbacks(volDownCheck);
                    handler.postDelayed(volDownCheck, 3000);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    volDownSince = 0;
                    handler.removeCallbacks(volDownCheck);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ---------- anti-theft power-menu gate ----------

    /** The system power menu (Emergency / Lock now / Power off / Restart)
     * is a SystemUI window whose text contains "Power off". When it opens
     * and anti-theft is on, close it and show the PIN gate instead.
     * Best effort: the exact window varies by phone model, so the user
     * must test it once on their own phone. */
    private void checkPowerMenu(AccessibilityEvent event) {
        try {
            SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
            if (!p.getBoolean("antitheft_on", false)) return;
            if (p.getString("antitheft_pin", "").isEmpty()) return;
            if (System.currentTimeMillis() < p.getLong("antitheft_grace_until", 0)) return;
            if (System.currentTimeMillis() - lastGateTime < 5000) return;
            CharSequence pkg = event.getPackageName();
            if (pkg == null || !pkg.toString().contains("systemui")) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                if (!subtreeHasText(root, "power off")) return;
            } finally {
                root.recycle();
            }
            lastGateTime = System.currentTimeMillis();
            // Close the menu, then ask for the PIN.
            performGlobalAction(GLOBAL_ACTION_BACK);
            Intent gate = new Intent(this, AntiTheftPinActivity.class);
            gate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(gate);
        } catch (Exception ignored) {}
    }

    // ---------- App Lock PIN gate (v4.9.12) ----------

    /** Track the foreground app (v4.9.14): when the user leaves the
     * unlocked app for a different app, its unlock grace ends at once
     * - so opening the app again always asks for the PIN. Our own
     * screens and the SystemUI shade (notification panel, Recents)
     * do NOT count as leaving, so checking a notification inside the
     * app does not re-lock it. */
    private void trackForeground(AccessibilityEvent event) {
        try {
            CharSequence pkgCs = event.getPackageName();
            if (pkgCs == null) return;
            String pkg = pkgCs.toString();
            if (pkg.equals(getPackageName())) return;
            if (pkg.contains("systemui")) return;
            if (lastPkg != null && !pkg.equals(lastPkg)) {
                SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
                if (lastPkg.equals(p.getString("applock_grace_pkg", ""))) {
                    p.edit().remove("applock_grace_pkg")
                            .remove("applock_grace_until").apply();
                }
            }
            lastPkg = pkg;
            // Auto-reply (v4.9.18): remember when a chat app was last on
            // screen, so the auto-reply can stay quiet while the owner
            // is online in WhatsApp / Telegram / Messenger himself.
            // Saved at most once every 30s - the timestamp only needs
            // to stay fresh while he is in the app.
            if (("com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg)
                    || "org.telegram.messenger".equals(pkg)
                    || "com.facebook.orca".equals(pkg))
                    && System.currentTimeMillis() - lastChatFgSave > 30000) {
                lastChatFgSave = System.currentTimeMillis();
                getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                        .putLong("chat_fg_time", lastChatFgSave).apply();
            }
        } catch (Exception ignored) {}
    }

    /** A locked app just came to the front: show the PIN gate instead.
     * Skips our own screens (the gate itself, MainActivity, More
     * Settings) and apps that are not locked. The unlock grace now
     * lives only while the user stays inside the unlocked app (v4.9.14)
     * - leave it for another app and the grace ends, so the next open
     * always asks again. */
    private void checkAppLock(AccessibilityEvent event) {
        try {
            SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
            if (!p.getBoolean("applock_on", false)) return;
            if (p.getString("applock_pin", "").isEmpty()) return;
            if (System.currentTimeMillis() - lastAppLockTime < 2000) return;
            CharSequence pkgCs = event.getPackageName();
            if (pkgCs == null) return;
            String pkg = pkgCs.toString();
            if (pkg.equals(getPackageName())) return;
            Set<String> locked = p.getStringSet("applock_apps", null);
            if (locked == null || !locked.contains(pkg)) return;
            if (pkg.equals(p.getString("applock_grace_pkg", ""))
                    && pkg.equals(lastPkg)) return;
            lastAppLockTime = System.currentTimeMillis();
            Intent gate = new Intent(this, AppLockActivity.class);
            gate.putExtra("lock_pkg", pkg);
            gate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(gate);
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    // ---------- called from CommandEngine (same process) ----------

    /** Is the accessibility service switched on for this app? */
    public static boolean isEnabled(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null
                    && enabled.contains(ctx.getPackageName())
                    && enabled.contains("BlackMaskAccessibilityService");
        } catch (Exception e) {
            return false;
        }
    }

    /** Queue an auto-tap for the next matching screen (expires in 15s). */
    public static void setPendingAction(Context ctx, String action) {
        SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        p.edit().putString("pending_auto", action).apply();
        if (pendingClearTask != null) staticHandler.removeCallbacks(pendingClearTask);
        pendingClearTask = () -> p.edit().remove("pending_auto")
                .remove("loc_panel_open").apply();
        staticHandler.postDelayed(pendingClearTask, 15000);
    }

    /** Run a global action (screenshot, lock, home, back, notifications). */
    public static boolean doGlobal(String action) {
        if (instance == null) return false;
        switch (action) {
            case "screenshot": instance.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT); return true;
            case "lock": instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); return true;
            case "home": instance.performGlobalAction(GLOBAL_ACTION_HOME); return true;
            case "back": instance.performGlobalAction(GLOBAL_ACTION_BACK); return true;
            case "recents": instance.performGlobalAction(GLOBAL_ACTION_RECENTS); return true;
            case "notifications": instance.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); return true;
            case "quick_settings": instance.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); return true;
            case "scroll_down": instance.scrollBy(1); return true;
            case "scroll_up": instance.scrollBy(-1); return true;
            default: return false;
        }
    }

    /** Swipe the screen: direction +1 = scroll down, -1 = scroll up.
     * Works in YouTube, Instagram, Facebook - any app. */
    private void scrollBy(int direction) {
        try {
            float w = getResources().getDisplayMetrics().widthPixels;
            float h = getResources().getDisplayMetrics().heightPixels;
            float x = w / 2f;
            float y1 = direction > 0 ? h * 0.75f : h * 0.25f;
            float y2 = direction > 0 ? h * 0.25f : h * 0.75f;
            Path p = new Path();
            p.moveTo(x, y1);
            p.lineTo(x, y2);
            GestureDescription.Builder gb = new GestureDescription.Builder();
            gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 300));
            dispatchGesture(gb.build(), null, null);
        } catch (Exception ignored) {}
    }

    private void clearPending() {
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().remove("pending_auto").apply();
    }

    /** The Location panel was handled - clear the flag the PIN gate watches. */
    private void clearLocPanelFlag() {
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().remove("loc_panel_open").apply();
    }

    // ---------- node finding ----------

    /** A switch near given text ("wi-fi", "location"), else the first switch. */
    private AccessibilityNodeInfo findSwitchNear(AccessibilityNodeInfo root, String needle) {
        List<AccessibilityNodeInfo> switches = new ArrayList<>();
        Queue<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            CharSequence cls = n.getClassName();
            if (cls != null && (cls.toString().endsWith("Switch")
                    || cls.toString().endsWith("SwitchCompat"))) {
                switches.add(n);
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        if (switches.isEmpty()) return null;
        for (AccessibilityNodeInfo sw : switches) {
            AccessibilityNodeInfo parent = sw.getParent();
            if (parent != null && subtreeHasText(parent, needle)) return sw;
            if (subtreeHasText(sw, needle)) return sw;
        }
        return switches.get(0);
    }

    private boolean subtreeHasText(AccessibilityNodeInfo node, String needle) {
        if (node == null) return false;
        Queue<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(node);
        int seen = 0;
        while (!q.isEmpty() && seen < 200) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            seen++;
            CharSequence t = n.getText();
            if (t != null && t.toString().toLowerCase(Locale.ENGLISH).contains(needle)) return true;
            CharSequence d = n.getContentDescription();
            if (d != null && d.toString().toLowerCase(Locale.ENGLISH).contains(needle)) return true;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return false;
    }

    /** A button whose description or text contains one of these words. */
    private AccessibilityNodeInfo findByDescription(AccessibilityNodeInfo root, String... words) {
        Queue<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < 300) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            seen++;
            String hay = "";
            if (n.getContentDescription() != null) hay += n.getContentDescription().toString().toLowerCase(Locale.ENGLISH) + " ";
            if (n.getText() != null) hay += n.getText().toString().toLowerCase(Locale.ENGLISH);
            for (String w : words) {
                if (hay.contains(w) && n.isClickable()) return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    /** Tap near the bottom-center of the screen (camera shutter position). */
    private void tapBottomCenter() {
        try {
            float x = getResources().getDisplayMetrics().widthPixels / 2f;
            float y = getResources().getDisplayMetrics().heightPixels * 0.88f;
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder gb = new GestureDescription.Builder();
            gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
            dispatchGesture(gb.build(), null, null);
        } catch (Exception ignored) {}
    }
}
