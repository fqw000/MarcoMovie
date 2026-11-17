package com.pilipala.marcomovie;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Conservative heuristic ad-segment detection for media playlists whose ad inserts cannot be
 * described by static rules or the industry-standard SCTE-35/CUE markers handled in {@link
 * M3u8AdFilter}.
 *
 * <p>The reliable signals (URL rules, SCTE/CUE markers, KEY-NONE clear blocks inside encrypted
 * streams) are the default. The duration-window, URL-prefix and mirror-reuse detectors are too
 * eager on real-world playlists, so they are opt-in and disabled by default; when enabled they
 * require strong, bounded evidence rather than treating any short or repeated segment as an ad.
 */
final class HeuristicAdDetector {
    private HeuristicAdDetector() {}

    static Set<Integer> detect(
            List<M3u8AdFilter.Segment> segments, AdFilterRules.Heuristics cfg, boolean encrypted) {
        Set<Integer> remove = new HashSet<>();
        if (cfg == null || !cfg.enabled || segments == null || segments.isEmpty()) return remove;

        // Removing segments inside an encrypted stream shifts implicit IVs, so only the clear-block
        // detector is safe there. The remaining detectors run on clear streams only.
        if (encrypted) {
            if (cfg.keyNoneSwitch) remove.addAll(detectKeyNoneSwitch(segments, cfg));
            return remove;
        }
        if (cfg.urlPrefixAnomaly) remove.addAll(detectHostAnomaly(segments, cfg));
        if (cfg.mirrorReuse) remove.addAll(detectMirrorReuse(segments, cfg));
        if (cfg.durationWindows) remove.addAll(detectDurationWindows(segments, cfg));
        return remove;
    }

    private static boolean isClear(M3u8AdFilter.Segment segment) {
        return segment.keyMethod == null || "NONE".equals(segment.keyMethod);
    }

    /** Removes contiguous clear blocks that sit inside an otherwise encrypted stream. */
    private static Set<Integer> detectKeyNoneSwitch(
            List<M3u8AdFilter.Segment> segments, AdFilterRules.Heuristics cfg) {
        Set<Integer> remove = new HashSet<>();
        int start = 0;
        while (start < segments.size()) {
            if (!isClear(segments.get(start))) {
                start++;
                continue;
            }
            int end = start;
            while (end < segments.size() && isClear(segments.get(end))) end++;
            int length = end - start;
            boolean boundedLeft = start > 0 && !isClear(segments.get(start - 1));
            boolean boundedRight = end < segments.size() && !isClear(segments.get(end));
            if (length >= cfg.minAdBlockSegments
                    && length <= cfg.maxAdBlockSegments
                    && (boundedLeft || boundedRight)) {
                for (int i = start; i < end; i++) remove.add(i);
            }
            start = end;
        }
        return remove;
    }

    /**
     * Removes short contiguous runs served from a different host than the playlist's dominant host,
     * only when the run is bounded on both sides by the dominant host (classic ad-CD insert).
     */
    private static Set<Integer> detectHostAnomaly(
            List<M3u8AdFilter.Segment> segments, AdFilterRules.Heuristics cfg) {
        Set<Integer> remove = new HashSet<>();
        String dominantHost = dominantHost(segments);
        if (dominantHost == null || dominantHost.isEmpty()) return remove;
        int start = 0;
        while (start < segments.size()) {
            if (dominantHost.equals(hostOf(segments.get(start).uri))) {
                start++;
                continue;
            }
            int end = start;
            while (end < segments.size() && !dominantHost.equals(hostOf(segments.get(end).uri)))
                end++;
            int length = end - start;
            boolean boundedLeft =
                    start > 0 && dominantHost.equals(hostOf(segments.get(start - 1).uri));
            boolean boundedRight =
                    end < segments.size() && dominantHost.equals(hostOf(segments.get(end).uri));
            if (length >= cfg.minAdBlockSegments
                    && length <= cfg.maxAdBlockSegments
                    && boundedLeft
                    && boundedRight) {
                for (int i = start; i < end; i++) remove.add(i);
            }
            start = end;
        }
        return remove;
    }

    /**
     * Removes every occurrence of a URI that repeats heavily (ad-loop evidence) while the rest of
     * the playlist is mostly unique. Requires at least three repeats, at least four distinct URIs,
     * and short segment durations so ordinary repeated placeholders do not match.
     */
    private static Set<Integer> detectMirrorReuse(
            List<M3u8AdFilter.Segment> segments, AdFilterRules.Heuristics cfg) {
        Set<Integer> remove = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        for (M3u8AdFilter.Segment segment : segments) {
            if (segment.uri == null || segment.uri.isEmpty()) continue;
            Integer seen = counts.get(segment.uri);
            counts.put(segment.uri, (seen == null ? 0 : seen) + 1);
        }
        if (counts.size() < 4) return remove;
        for (int i = 0; i < segments.size(); i++) {
            M3u8AdFilter.Segment segment = segments.get(i);
            if (segment.uri == null || segment.uri.isEmpty()) continue;
            Integer seen = counts.get(segment.uri);
            if (seen != null
                    && seen >= 3
                    && segment.durationSec > 0f
                    && segment.durationSec <= cfg.maxAdBlockDurationSec) {
                remove.add(i);
            }
        }
        return remove;
    }

    /**
     * Removes a short, uniformly-timed block of segments (2..{@code maxAdBlockSegments}) that is
     * bounded on both sides by clearly longer content, with no discontinuity inside. Disabled by
     * default: segment duration alone is an unreliable ad signal in HLS.
     */
    private static Set<Integer> detectDurationWindows(
            List<M3u8AdFilter.Segment> segments, AdFilterRules.Heuristics cfg) {
        Set<Integer> remove = new HashSet<>();
        int start = 0;
        while (start < segments.size()) {
            if (!isAdLikeDuration(segments.get(start), cfg)) {
                start++;
                continue;
            }
            int end = start;
            while (end < segments.size() && isAdLikeDuration(segments.get(end), cfg)) end++;
            int length = end - start;
            boolean boundedLeft = start > 0 && isLongContent(segments.get(start - 1), cfg);
            boolean boundedRight = end < segments.size() && isLongContent(segments.get(end), cfg);
            boolean noDiscontinuity = true;
            for (int i = start; i < end; i++) {
                if (segments.get(i).discontinuityBefore > 0) {
                    noDiscontinuity = false;
                    break;
                }
            }
            if (length >= cfg.minAdBlockSegments
                    && length <= cfg.maxAdBlockSegments
                    && boundedLeft
                    && boundedRight
                    && noDiscontinuity) {
                for (int i = start; i < end; i++) remove.add(i);
            }
            start = end;
        }
        return remove;
    }

    private static boolean isAdLikeDuration(
            M3u8AdFilter.Segment segment, AdFilterRules.Heuristics cfg) {
        float duration = segment.durationSec;
        return duration >= cfg.minAdBlockDurationSec && duration <= cfg.maxAdBlockDurationSec;
    }

    private static boolean isLongContent(
            M3u8AdFilter.Segment segment, AdFilterRules.Heuristics cfg) {
        return segment.durationSec > cfg.maxAdBlockDurationSec;
    }

    private static String dominantHost(List<M3u8AdFilter.Segment> segments) {
        Map<String, Integer> hosts = new HashMap<>();
        for (M3u8AdFilter.Segment segment : segments) {
            String host = hostOf(segment.uri);
            if (host == null || host.isEmpty()) continue;
            Integer seen = hosts.get(host);
            hosts.put(host, (seen == null ? 0 : seen) + 1);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : hosts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    /** Extracts the host portion of an absolute {@code http(s)://host[:port]/...} URL. */
    private static String hostOf(String uri) {
        if (uri == null) return null;
        int scheme = uri.indexOf("://");
        int start = scheme >= 0 ? scheme + 3 : 0;
        int end = start;
        while (end < uri.length()) {
            char c = uri.charAt(end);
            if (c == '/' || c == '?' || c == '#' || c == ':') break;
            end++;
        }
        String host = uri.substring(start, end).trim();
        return host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
    }
}
