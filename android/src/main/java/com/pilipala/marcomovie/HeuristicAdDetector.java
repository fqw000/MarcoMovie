package com.pilipala.marcomovie;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Heuristic ad-segment detection for media playlists whose ad inserts cannot be described by
 * static regex/keyword rules. The four detectors mirror the well-known takagen99-style m3u8 ad
 * filter heuristics: clear blocks inside encrypted streams, anomalous URL prefixes, isolated
 * short-duration windows, and repeated (mirror) segment URIs.
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
        if (cfg.urlPrefixAnomaly) remove.addAll(detectUrlPrefixAnomaly(segments, cfg));
        if (cfg.mirrorReuse) remove.addAll(detectMirrorReuse(segments));
        remove.addAll(detectDurationWindows(segments, cfg));
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
            if (length >= cfg.minAdBlockSegments && (boundedLeft || boundedRight)) {
                for (int i = start; i < end; i++) remove.add(i);
            }
            start = end;
        }
        return remove;
    }

    /** Removes contiguous runs whose URL prefix diverges from the playlist's dominant prefix. */
    private static Set<Integer> detectUrlPrefixAnomaly(
            List<M3u8AdFilter.Segment> segments, AdFilterRules.Heuristics cfg) {
        Set<Integer> remove = new HashSet<>();
        String prefix = dominantPrefix(segments);
        if (prefix == null || prefix.isEmpty()) return remove;
        int start = 0;
        while (start < segments.size()) {
            if (segments.get(start).uri.startsWith(prefix)) {
                start++;
                continue;
            }
            int end = start;
            while (end < segments.size() && !segments.get(end).uri.startsWith(prefix)) end++;
            int length = end - start;
            boolean boundedLeft = start > 0 && segments.get(start - 1).uri.startsWith(prefix);
            boolean boundedRight =
                    end < segments.size() && segments.get(end).uri.startsWith(prefix);
            if (length >= cfg.minAdBlockSegments && (boundedLeft || boundedRight)) {
                for (int i = start; i < end; i++) remove.add(i);
            }
            start = end;
        }
        return remove;
    }

    /** Removes every occurrence of a URI that repeats across the playlist (ad loop evidence). */
    private static Set<Integer> detectMirrorReuse(List<M3u8AdFilter.Segment> segments) {
        Set<Integer> remove = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        for (M3u8AdFilter.Segment segment : segments) {
            if (segment.uri == null || segment.uri.isEmpty()) continue;
            counts.put(segment.uri, counts.getOrDefault(segment.uri, 0) + 1);
        }
        if (counts.size() <= 1) return remove;
        for (int i = 0; i < segments.size(); i++) {
            String uri = segments.get(i).uri;
            if (uri != null && counts.getOrDefault(uri, 0) >= 2) remove.add(i);
        }
        return remove;
    }

    /** Removes isolated windows of short-duration segments sandwiched between longer content. */
    private static Set<Integer> detectDurationWindows(
            List<M3u8AdFilter.Segment> segments, AdFilterRules.Heuristics cfg) {
        Set<Integer> remove = new HashSet<>();
        float contentDuration = 0f;
        for (M3u8AdFilter.Segment segment : segments) {
            if (segment.durationSec > contentDuration) contentDuration = segment.durationSec;
        }
        if (contentDuration <= 0f) return remove;
        int start = 0;
        while (start < segments.size()) {
            if (!isAdLikeDuration(segments.get(start), cfg, contentDuration)) {
                start++;
                continue;
            }
            int end = start;
            while (end < segments.size()
                    && isAdLikeDuration(segments.get(end), cfg, contentDuration)) end++;
            int length = end - start;
            boolean boundedContentLeft =
                    start > 0 && !isAdLikeDuration(segments.get(start - 1), cfg, contentDuration);
            boolean boundedContentRight =
                    end < segments.size()
                            && !isAdLikeDuration(segments.get(end), cfg, contentDuration);
            if (length >= cfg.minAdBlockSegments && (boundedContentLeft || boundedContentRight)) {
                for (int i = start; i < end; i++) remove.add(i);
            }
            start = end;
        }
        return remove;
    }

    private static boolean isAdLikeDuration(
            M3u8AdFilter.Segment segment, AdFilterRules.Heuristics cfg, float contentDuration) {
        float duration = segment.durationSec;
        return duration > 0f
                && duration >= cfg.minAdBlockDurationSec
                && duration <= cfg.maxAdBlockDurationSec
                && duration < contentDuration;
    }

    private static String dominantPrefix(List<M3u8AdFilter.Segment> segments) {
        Map<String, Integer> prefixes = new HashMap<>();
        for (M3u8AdFilter.Segment segment : segments) {
            String prefix = urlPrefix(segment.uri);
            if (prefix == null || prefix.isEmpty()) continue;
            prefixes.put(prefix, prefixes.getOrDefault(prefix, 0) + 1);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : prefixes.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static String urlPrefix(String uri) {
        if (uri == null) return null;
        int slash = uri.lastIndexOf('/');
        if (slash < 0) return null;
        return uri.substring(0, slash + 1);
    }
}
