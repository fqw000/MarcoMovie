package com.pilipala.marcomovie;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads the bundled default ad-filter rule set from {@code assets/adfilter/builtin_rules.json}. */
final class BuiltinAdRules {
    private static volatile String cachedJson;

    private BuiltinAdRules() {}

    static AdFilterRules load(Context context) {
        return AdFilterRules.parse(json(context));
    }

    static String json(Context context) {
        String json = cachedJson;
        if (json == null) {
            json = readAsset(context);
            cachedJson = json == null ? "" : json;
        }
        return json;
    }

    private static String readAsset(Context context) {
        try (InputStream in = context.getAssets().open("adfilter/builtin_rules.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }
}
