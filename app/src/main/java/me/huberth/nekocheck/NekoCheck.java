package me.huberth.nekocheck;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class NekoCheck implements IXposedHookLoadPackage {

    private static final String TAG = "NekoCheck";
    private static final String PAYLOAD_PREFIX = "741ad28818eab17668bc2c70bd419fc25ff56481758a4ac87e7ca164fb6ae1b1";

    // {targetClass, trigger, collect, onceFlag, botHelper, botEntry, botSend}
    private static final HashMap<Integer, String[]> KNOWN_BUILDS = new HashMap<>();
    private static final HashMap<String, String[]> KNOWN_VERSIONS = new HashMap<>();
    static {
        KNOWN_BUILDS.put(53350, new String[]{"mN0", "c", "j", "n", "Le1", "i", "j"});  // 11.2.3 Telegram
        KNOWN_BUILDS.put(63010, new String[]{"uo5", "b", "f", "k", "pb7", "j", "k"});  // 12.2.3 Telegram
        KNOWN_BUILDS.put(65970, new String[]{"uo5", "c", "g", "l", "dc7", "l", "m"});  // 12.5.2 Telegram
        KNOWN_BUILDS.put(65972, new String[]{"lp5", "c", "g", "l", "hd7", "l", "m"});  // 12.5.2 Google Play

        KNOWN_VERSIONS.put("11.2.3", new String[]{"mN0", "c", "j", "n", "Le1", "i", "j"}); // First appearance - China only
        KNOWN_VERSIONS.put("12.2.3", new String[]{"uo5", "b", "f", "k", "pb7", "j", "k"}); // First appearance - Worldwide
        KNOWN_VERSIONS.put("12.5.2", new String[]{"uo5", "c", "g", "l", "dc7", "l", "m"}); // Latest version
    }

    private Context appContext;
    private String[] mapping;
    private int collectCount;
    private long lastToastMs;

    // Config values
    private boolean cfgResetOnceGuard;
    private boolean cfgBypassCountryFilter;
    private final String[] cfgSpoofEntries = new String[8]; // null = not spoofing, format: "userId:phone"

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        Log.i(TAG, "Loaded for " + param.packageName);

        // sendRequest hook works on every version
        installNetworkHook(param.classLoader);

        // Version-specific hooks need app context
        final ClassLoader classLoader = param.classLoader;
        try {
            XposedHelpers.findAndHookMethod(
                    android.app.Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    appContext = (Context) param.thisObject;
                    onAppReady(classLoader);
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "Failed to hook Application.onCreate: " + e);
        }
    }

    private void onAppReady(ClassLoader classLoader) {
        loadConfig();
        resolveMapping();

        if (mapping != null) {
            installTriggerHook(classLoader);
            installCollectHook(classLoader);
            installBotHook(classLoader, mapping[5], "BOT ENTRY");
            installBotHook(classLoader, mapping[6], "QUERY");
        }

        if (cfgBypassCountryFilter && versionCode() == 53350) {
            installCountryFilterBypass(classLoader);
        }
    }

    private void loadConfig() {
        SharedPreferences prefs = appContext.getSharedPreferences("nekocheck", Context.MODE_PRIVATE);

        if (!prefs.contains("reset_once_guard")) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("reset_once_guard", true);
            editor.putBoolean("bypass_country_filter", true);
            for (int i = 0; i < 8; i++) editor.putString("spoof_" + i, "");
            editor.apply();
        }

        cfgResetOnceGuard = prefs.getBoolean("reset_once_guard", true);
        cfgBypassCountryFilter = prefs.getBoolean("bypass_country_filter", true);
        for (int i = 0; i < 8; i++) {
            String value = prefs.getString("spoof_" + i, "");
            cfgSpoofEntries[i] = value.isEmpty() ? null : value;
        }

        Log.i(TAG, "Config: reset_once_guard=" + cfgResetOnceGuard
                + " bypass_country_filter=" + cfgBypassCountryFilter);
        for (int i = 0; i < 8; i++)
            if (cfgSpoofEntries[i] != null)
                Log.i(TAG, "  spoof_" + i + "=" + cfgSpoofEntries[i]);
    }

    private void resolveMapping() {
        int code = versionCode();
        String appVersion = versionName();

        mapping = code > 0 ? KNOWN_BUILDS.get(code) : null;
        if (mapping != null) {
            Log.i(TAG, "Matched build " + code + " (" + appVersion + ")");
            return;
        }

        mapping = appVersion != null ? KNOWN_VERSIONS.get(appVersion) : null;
        if (mapping != null) {
            Log.i(TAG, "Matched version " + appVersion);
            return;
        }

        Log.i(TAG, "Unknown build " + code + " (" + appVersion + ") — network hook still active");
    }

    private void installNetworkHook(ClassLoader classLoader) {
        try {
            Class<?> connectionsManager = classLoader.loadClass("org.telegram.tgnet.ConnectionsManager");
            Class<?> requestDelegate = classLoader.loadClass("org.telegram.tgnet.RequestDelegate");

            for (Method method : connectionsManager.getDeclaredMethods()) {
                if (!"sendRequest".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 3 && params[1] == requestDelegate && params[2] == int.class) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            interceptRequest(param.args[0]);
                        }
                    });
                    Log.i(TAG, "Hooked sendRequest");
                    return;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Network hook failed: " + e);
        }
    }

    private void interceptRequest(Object request) {
        if (request == null) return;
        try {
            for (Field field : request.getClass().getDeclaredFields()) {
                if (field.getType() != String.class) continue;
                field.setAccessible(true);
                String value = (String) field.get(request);
                if (value == null || !value.contains(PAYLOAD_PREFIX)) continue;

                String spoofed = applySpoof(value);
                if (!spoofed.equals(value)) {
                    field.set(request, spoofed);
                    Log.w(TAG, "INTERCEPTED (spoofed)");
                    Log.w(TAG, "  original: " + value);
                    Log.w(TAG, "  sent:     " + spoofed);
                } else {
                    Log.w(TAG, "INTERCEPTED");
                    Log.w(TAG, "  sent: " + value);
                }

                // Toast shows what actually goes to the bot
                String sent = (String) field.get(request);
                String payload = sent != null ? sent.substring(PAYLOAD_PREFIX.length()).trim() : "";
                toast("SENT: " + payload);
                return;
            }
        } catch (Throwable ignored) {}
    }

    private String applySpoof(String payload) {
        boolean hasSpoof = false;
        for (String entry : cfgSpoofEntries) if (entry != null) { hasSpoof = true; break; }
        if (!hasSpoof) return payload;

        int prefixEnd = payload.indexOf('{');
        if (prefixEnd < 0) return payload;

        try {
            JSONObject json = new JSONObject();
            for (String entry : cfgSpoofEntries) {
                if (entry == null) continue;
                String[] parts = entry.split(":", 2);
                if (parts.length == 2) json.put(parts[0], parts[1]);
            }
            return payload.substring(0, prefixEnd) + json.toString();
        } catch (Throwable e) {
            return payload;
        }
    }

    private void installTriggerHook(ClassLoader classLoader) {
        try {
            Class<?> targetClass = classLoader.loadClass(mapping[0]);
            String methodName = mapping[1];
            for (Method method : targetClass.getDeclaredMethods()) {
                if (!method.getName().equals(methodName) || !Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0) continue;
                final String label = mapping[0] + "." + methodName + "()";
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Log.w(TAG, "TRIGGER " + label);
                    }
                });
                Log.i(TAG, "Hooked trigger: " + label);
                return;
            }
        } catch (Throwable ignored) {}
    }

    private void installCollectHook(ClassLoader classLoader) {
        try {
            final Class<?> targetClass = classLoader.loadClass(mapping[0]);
            String methodName = mapping[2];
            final String flagField = mapping[3];

            for (Method method : targetClass.getDeclaredMethods()) {
                if (!method.getName().equals(methodName) || !Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class || method.getParameterCount() != 0) continue;

                final String label = mapping[0] + "." + methodName + "()";
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        collectCount++;
                        boolean guard = readBool(targetClass, flagField);
                        Log.w(TAG, "COLLECT #" + collectCount + " " + label + " guard=" + guard);
                        if (cfgResetOnceGuard && guard) {
                            writeBool(targetClass, flagField, false);
                            Log.w(TAG, "  guard reset");
                        }
                    }
                });
                Log.i(TAG, "Hooked collect: " + label);
                return;
            }
        } catch (Throwable ignored) {}
    }

    private void installBotHook(ClassLoader classLoader, String methodName, final String logPrefix) {
        try {
            Class<?> botClass = classLoader.loadClass(mapping[4]);

            for (Method method : botClass.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) continue;
                int strIdx = -1;
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (params[i] == String.class) { strIdx = i; break; }
                }
                if (strIdx < 0) continue;

                final String label = mapping[4] + "." + methodName + "(" + params.length + ")";
                final int queryIdx = strIdx;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String query = (String) param.args[queryIdx];
                        if (query == null) return;
                        if (query.contains(PAYLOAD_PREFIX)) {
                            Log.w(TAG, logPrefix + " [" + label + "]: " + query);
                        } else {
                            Log.d(TAG, logPrefix + " [" + label + "]: " + query);
                        }
                    }
                });
                Log.i(TAG, "Hooked " + logPrefix.toLowerCase() + ": " + label);
                return;
            }
        } catch (Throwable ignored) {}
    }

    private void installCountryFilterBypass(ClassLoader classLoader) {
        try {
            final Field isChineseUser = classLoader.loadClass("d92").getDeclaredField("B0");
            isChineseUser.setAccessible(true);

            for (Method method : classLoader.loadClass("mN0").getDeclaredMethods()) {
                if (!"j".equals(method.getName()) || !Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class || method.getParameterCount() != 0) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            isChineseUser.setBoolean(null, true);
                            Log.i(TAG, "Country filter bypassed");
                        } catch (Throwable ignored) {}
                    }
                });
                Log.i(TAG, "Hooked country filter [11.2.3]");
                return;
            }
        } catch (Throwable ignored) {}
    }

    private void toast(final String message) {
        if (appContext == null) return;
        long now = System.currentTimeMillis();
        if (now - lastToastMs < 50) return; // debounce double triggers
        lastToastMs = now;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private int versionCode() {
        try { return appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionCode; }
        catch (Throwable e) { return -1; }
    }

    private String versionName() {
        try { return appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName; }
        catch (Throwable e) { return null; }
    }

    private static boolean readBool(Class<?> cls, String fieldName) {
        try { Field f = cls.getDeclaredField(fieldName); f.setAccessible(true); return f.getBoolean(null); }
        catch (Throwable e) { return false; }
    }

    private static void writeBool(Class<?> cls, String fieldName, boolean value) {
        try { Field f = cls.getDeclaredField(fieldName); f.setAccessible(true); f.setBoolean(null, value); }
        catch (Throwable ignored) {}
    }
}
