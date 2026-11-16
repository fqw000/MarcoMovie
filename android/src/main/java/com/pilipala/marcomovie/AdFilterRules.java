package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Ad-filter rule model for HLS playlist and request filtering. The JSON schema is produced by
 * {@link RulesImporter} and consumed by {@link M3u8AdFilter} and {@link AdFilterDataSource}.
 *
 * <p>Rules are split into four layers: global playlist regex/keywords (applied to every media
 * playlist), host-scoped rules (applied only to playlists served by a matching host), blocked
 * domains/URLs (applied to every request), and optional heuristics for detecting ad segments that
 * rules cannot describe.
 */
final class AdFilterRules {
    static final class HostRule {
        final String host;
        final List<Pattern> regex;
        final List<String> keywords;

        HostRule(String host, List<Pattern> regex, List<String> keywords) {
            this.host = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
            this.regex = regex;
            this.keywords = keywords;
        }

        boolean matches(String uri) {
            for (String keyword : keywords) {
                if (!keyword.isEmpty() && uri.contains(keyword)) return true;
            }
            for (Pattern pattern : regex) {
                if (pattern.matcher(uri).find()) return true;
            }
            return false;
        }
    }

    static final class Heuristics {
        boolean enabled;
        int minAdBlockDurationSec = 3;
        int maxAdBlockDurationSec = 75;
        int minAdBlockSegments = 2;
        boolean urlPrefixAnomaly = true;
        boolean keyNoneSwitch = true;
        boolean mirrorReuse = true;
    }

    final List<Pattern> globalRegex;
    final List<String> globalKeywords;
    final List<HostRule> hostRules;
    final Set<String> blockedDomains;
    final List<Pattern> blockedUrlPatterns;
    final Heuristics heuristics;

    private AdFilterRules(
            List<Pattern> globalRegex,
            List<String> globalKeywords,
            List<HostRule> hostRules,
            Set<String> blockedDomains,
            List<Pattern> blockedUrlPatterns,
            Heuristics heuristics) {
        this.globalRegex = globalRegex;
        this.globalKeywords = globalKeywords;
        this.hostRules = hostRules;
        this.blockedDomains = blockedDomains;
        this.blockedUrlPatterns = blockedUrlPatterns;
        this.heuristics = heuristics;
    }

    static AdFilterRules empty() {
        return new AdFilterRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptySet(),
                Collections.emptyList(),
                new Heuristics());
    }

    static AdFilterRules parse(String json) {
        if (json == null || json.trim().isEmpty()) return empty();
        try {
            JSONObject root = new JSONObject(json);
            List<Pattern> globalRegex = new ArrayList<>();
            List<String> globalKeywords = new ArrayList<>();
            JSONArray playlist = root.optJSONArray("playlistRules");
            if (playlist != null) {
                for (int i = 0; i < playlist.length(); i++) {
                    JSONObject rule = playlist.optJSONObject(i);
                    if (rule == null) continue;
                    String type = rule.optString("type", "");
                    String pattern = rule.optString("pattern", "");
                    if (pattern.isEmpty()) continue;
                    if ("keyword".equals(type)) {
                        globalKeywords.add(pattern);
                    } else {
                        Pattern compiled = compile(pattern);
                        if (compiled != null) globalRegex.add(compiled);
                    }
                }
            }
            List<HostRule> hostRules = new ArrayList<>();
            JSONArray hosts = root.optJSONArray("hostRules");
            if (hosts != null) {
                for (int i = 0; i < hosts.length(); i++) {
                    JSONObject rule = hosts.optJSONObject(i);
                    if (rule == null) continue;
                    String host = rule.optString("host", "");
                    if (host.isEmpty()) continue;
                    List<Pattern> regex = new ArrayList<>();
                    JSONArray regexArr = rule.optJSONArray("regex");
                    if (regexArr != null) {
                        for (int j = 0; j < regexArr.length(); j++) {
                            Pattern compiled = compile(regexArr.optString(j, ""));
                            if (compiled != null) regex.add(compiled);
                        }
                    }
                    List<String> keywords = new ArrayList<>();
                    JSONArray kwArr = rule.optJSONArray("keywords");
                    if (kwArr != null) {
                        for (int j = 0; j < kwArr.length(); j++) {
                            String kw = kwArr.optString(j, "");
                            if (!kw.isEmpty()) keywords.add(kw);
                        }
                    }
                    hostRules.add(new HostRule(host, regex, keywords));
                }
            }
            Set<String> domains = new HashSet<>();
            JSONArray domainArr = root.optJSONArray("blockedDomains");
            if (domainArr != null) {
                for (int i = 0; i < domainArr.length(); i++) {
                    String domain = domainArr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                    if (domain.startsWith("*.")) domain = domain.substring(2);
                    if (!domain.isEmpty()) domains.add(domain);
                }
            }
            List<Pattern> urlPatterns = new ArrayList<>();
            JSONArray urlArr = root.optJSONArray("blockedUrlPatterns");
            if (urlArr != null) {
                for (int i = 0; i < urlArr.length(); i++) {
                    Pattern compiled = compile(urlArr.optString(i, ""));
                    if (compiled != null) urlPatterns.add(compiled);
                }
            }
            Heuristics heuristics = new Heuristics();
            JSONObject heu = root.optJSONObject("heuristics");
            if (heu != null) {
                heuristics.enabled = heu.optBoolean("enabled", false);
                heuristics.minAdBlockDurationSec = heu.optInt("minAdBlockDurationSec", 3);
                heuristics.maxAdBlockDurationSec = heu.optInt("maxAdBlockDurationSec", 75);
                heuristics.minAdBlockSegments = heu.optInt("minAdBlockSegments", 2);
                heuristics.urlPrefixAnomaly = heu.optBoolean("urlPrefixAnomaly", true);
                heuristics.keyNoneSwitch = heu.optBoolean("keyNoneSwitch", true);
                heuristics.mirrorReuse = heu.optBoolean("mirrorReuse", true);
            }
            return new AdFilterRules(
                    globalRegex, globalKeywords, hostRules, domains, urlPatterns, heuristics);
        } catch (Exception ignored) {
            return empty();
        }
    }

    private static Pattern compile(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) return null;
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException ignored) {
            return null;
        }
    }

    boolean hasAnyRule() {
        return !globalRegex.isEmpty()
                || !globalKeywords.isEmpty()
                || !hostRules.isEmpty()
                || !blockedDomains.isEmpty()
                || !blockedUrlPatterns.isEmpty();
    }

    boolean isBlockedHost(String host) {
        if (host == null || blockedDomains.isEmpty()) return false;
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return blockedDomains.contains(normalized) || endsWithAnyDomain(normalized);
    }

    private boolean endsWithAnyDomain(String host) {
        for (String domain : blockedDomains) {
            if (host.endsWith("." + domain)) return true;
        }
        return false;
    }

    boolean isBlockedUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        for (Pattern pattern : blockedUrlPatterns) {
            if (pattern.matcher(url).find()) return true;
        }
        return false;
    }

    boolean matchesSegment(String playlistHost, String uri) {
        if (uri == null || uri.isEmpty()) return false;
        for (String keyword : globalKeywords) {
            if (uri.contains(keyword)) return true;
        }
        for (Pattern pattern : globalRegex) {
            if (pattern.matcher(uri).find()) return true;
        }
        if (!hostRules.isEmpty() && playlistHost != null) {
            String normalized = playlistHost.trim().toLowerCase(Locale.ROOT);
            for (HostRule rule : hostRules) {
                if (normalized.equals(rule.host) || normalized.endsWith("." + rule.host)) {
                    if (rule.matches(uri)) return true;
                }
            }
        }
        return false;
    }
}
