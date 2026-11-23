package com.blackmask.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * App language (Settings -> APP LANGUAGE): English (default), বাংলা, हिन्दी.
 * No API, no internet - the translations live in the app itself
 * (res/values/strings.xml, values-bn/strings.xml, values-hi/strings.xml).
 * This is separate from the Voice Language, which controls what language
 * the app speaks and listens in.
 */
public class LocaleHelper {

    /** The app language the user picked in Settings (en/bn/hi). */
    public static String getAppLang(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
        return p.getString("app_lang", "en");
    }

    /** Wrap the base context so an activity starts in the chosen language. */
    public static Context wrap(Context base) {
        Locale locale = new Locale(getAppLang(base));
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    /** Apply the chosen language to a context (used by the service). */
    public static void apply(Context ctx) {
        Locale locale = new Locale(getAppLang(ctx));
        Locale.setDefault(locale);
        Configuration config = new Configuration(ctx.getResources().getConfiguration());
        config.setLocale(locale);
        ctx.getResources().updateConfiguration(config, ctx.getResources().getDisplayMetrics());
    }
}
