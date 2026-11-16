package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Converts third-party rule sources (TVBox {@code XC.json}, anti-AD hosts lists, and
 * m3u8-proxy-style regex rule sets) into the unified internal {@link AdFilterRules} JSON schema.
 */
final class RulesImporter {
    private RulesImporter() {}

    /** Maps TVBox site configs to host-scoped regex rules. */
    static String fromXcJson(String xcJson) {
        if (xcJson == null || xcJson.trim().isEmpty()) return null;
        try {
            Object parsed = new JSONTokener(xcJson.trim()).nextValue();
            JSONArray sites = new JSONArray();
            if (parsed instanceof JSONArray) {
                sites = (JSONArray) parsed;
            } else if (parsed instanceof JSONObject) {
                sites.put(parsed);
            } else {
                return null;
            }
            JSONArray hostRules = new JSONArray();
            for (int i = 0; i < sites.length(); i++) {
                JSONObject site = sites.optJSONObject(i);
                if (site == null) continue;
                String host = site.optString("host", "").trim();
                if (host.isEmpty()) {
                    host = hostFromUrl(site.optString("api", site.optString("url", "")));
                }
                if (host.isEmpty()) continue;
                Set<String> regex = new LinkedHashSet<>();
                collectStrings(site.opt("filter"), regex);
                collectStrings(site.opt("rules"), regex);
                if (regex.isEmpty()) continue;
                JSONObject rule = new JSONObject();
                rule.put("host", host);
                rule.put("regex", new JSONArray(new ArrayList<>(regex)));
                rule.put("keywords", new JSONArray());
                hostRules.put(rule);
            }
            if (hostRules.length() == 0) return null;
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("playlistRules", new JSONArray());
            root.put("hostRules", hostRules);
            root.put("blockedDomains", new JSONArray());
            root.put("blockedUrlPatterns", new JSONArray());
            root.put("heuristics", defaultHeuristics());
            return root.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Maps an anti-AD / hosts-style text list to blocked domains. */
    static String fromAntiAd(String hostsText) {
        if (hostsText == null || hostsText.trim().isEmpty()) return null;
        Set<String> domains = new LinkedHashSet<>();
        for (String line : hostsText.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            int comment = trimmed.indexOf('#');
            if (comment >= 0) trimmed = trimmed.substring(0, comment).trim();
            String domain = domainFromHostsLine(trimmed);
            if (domain != null) domains.add(domain);
        }
        if (domains.isEmpty()) return null;
        JSONObject root = new JSONObject();
        root.put("version", 2);
        root.put("playlistRules", new JSONArray());
        root.put("hostRules", new JSONArray());
        root.put("blockedDomains", new JSONArray(new ArrayList<>(domains)));
        root.put("blockedUrlPatterns", new JSONArray());
        root.put("heuristics", defaultHeuristics());
        return root.toString();
    }

    /** Maps an m3u8-proxy-style rule JSON (regex/keyword lists) to global playlist rules. */
    static String fromM3u8Proxy(String rulesJson) {
        if (rulesJson == null || rulesJson.trim().isEmpty()) return null;
        try {
            Object parsed = new JSONTokener(rulesJson.trim()).nextValue();
            Set<String> regex = new LinkedHashSet<>();
            collectAllStrings(parsed, regex);
            if (regex.isEmpty()) return null;
            JSONArray playlist = new JSONArray();
            for (String pattern : regex) {
                JSONObject rule = new JSONObject();
                rule.put("type", "regex");
                rule.put("pattern", pattern);
                playlist.put(rule);
            }
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("playlistRules", playlist);
            root.put("hostRules", new JSONArray());
            root.put("blockedDomains", new JSONArray());
            root.put("blockedUrlPatterns", new JSONArray());
            root.put("heuristics", defaultHeuristics());
            return root.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Merges one or more internal-schema JSON documents into a single schema. */
    static String merge(List<String> internalJsons) {
        JSONArray playlist = new JSONArray();
        JSONArray hostRules = new JSONArray();
        JSONArray domains = new JSONArray();
        JSONArray urlPatterns = new JSONArray();
        Set<String> domainSet = new LinkedHashSet<>();
        Set<String> urlSet = new LinkedHashSet<>();
        for (String json : internalJsons) {
            if (json == null || json.trim().isEmpty()) continue;
            try {
                JSONObject root = new JSONObject(json);
                JSONArray p = root.optJSONArray("playlistRules");
                if (p != null) for (int i = 0; i < p.length(); i++) playlist.put(p.opt(i));
                JSONArray h = root.optJSONArray("hostRules");
                if (h != null) for (int i = 0; i < h.length(); i++) hostRules.put(h.opt(i));
                JSONArray d = root.optJSONArray("blockedDomains");
                if (d != null)
                    for (int i = 0; i < d.length(); i++) domainSet.add(d.optString(i, ""));
                JSONArray u = root.optJSONArray("blockedUrlPatterns");
                if (u != null) for (int i = 0; i < u.length(); i++) urlSet.add(u.optString(i, ""));
            } catch (Exception ignored) {
            }
        }
        for (String domain : domainSet) domains.put(domain);
        for (String pattern : urlSet) urlPatterns.put(pattern);
        JSONObject root = new JSONObject();
        root.put("version", 2);
        root.put("playlistRules", playlist);
        root.put("hostRules", hostRules);
        root.put("blockedDomains", domains);
        root.put("blockedUrlPatterns", urlPatterns);
        root.put("heuristics", defaultHeuristics());
        return root.toString();
    }

    private static JSONObject defaultHeuristics() {
        JSONObject heuristics = new JSONObject();
        heuristics.put("enabled", true);
        heuristics.put("minAdBlockDurationSec", 3);
        heuristics.put("maxAdBlockDurationSec", 75);
        heuristics.put("minAdBlockSegments", 2);
        heuristics.put("urlPrefixAnomaly", true);
        heuristics.put("keyNoneSwitch", true);
        heuristics.put("mirrorReuse", true);
        return heuristics;
    }

    private static String domainFromHostsLine(String line) {
        String[] parts = line.split("\\s+");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty() || part.startsWith("#") || part.startsWith("!")) continue;
            if (part.startsWith("0.0.0.0")
                    || part.startsWith("127.0.0.1")
                    || part.startsWith("::")) {
                continue;
            }
            String domain = part;
            if (domain.startsWith("*.")) domain = domain.substring(2);
            domain = domain.toLowerCase(Locale.ROOT);
            if (domain.isEmpty()) continue;
            return domain;
        }
        return null;
    }

    private static String hostFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        String candidate = url.trim();
        int scheme = candidate.indexOf("://");
        if (scheme >= 0) candidate = candidate.substring(scheme + 3);
        int slash = candidate.indexOf('/');
        if (slash >= 0) candidate = candidate.substring(0, slash);
        int colon = candidate.indexOf(':');
        if (colon >= 0) candidate = candidate.substring(0, colon);
        return candidate.toLowerCase(Locale.ROOT);
    }

    private static void collectStrings(Object value, Set<String> out) {
        if (value == null || value == JSONObject.NULL) return;
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) return;
            for (String line : text.split("\r?\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) out.add(trimmed);
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                Object element = array.opt(i);
                if (element instanceof String) {
                    String trimmed = ((String) element).trim();
                    if (!trimmed.isEmpty()) out.add(trimmed);
                }
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : object.keySet()) {
                Object element = object.opt(key);
                if (element instanceof String) {
                    String trimmed = ((String) element).trim();
                    if (!trimmed.isEmpty()) out.add(trimmed);
                }
            }
        }
    }

    private static void collectAllStrings(Object value, Set<String> out) {
        if (value == null || value == JSONObject.NULL) return;
        if (value instanceof String) {
            String trimmed = ((String) value).trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collectAllStrings(array.opt(i), out);
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : object.keySet()) collectAllStrings(object.opt(key), out);
        }
    }
}
