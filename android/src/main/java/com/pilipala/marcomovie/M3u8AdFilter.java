package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure-Java HLS media-playlist filter. Removes ad segments matched by {@link AdFilterRules} and
 * by {@link HeuristicAdDetector}, then rebuilds a structurally valid playlist.
 *
 * <p>This class has no Android or Media3 dependency so it can be exercised by the offline JVM
 * checks under {@code android/tests}.
 */
final class M3u8AdFilter {
    static final class Segment {
        String uri;
        float durationSec;
        String keyMethod; // null = no KEY seen, "NONE", or e.g. "AES-128"
        int discontinuityBefore; // #EXT-X-DISCONTINUITY markers since previous segment
        boolean scteAd; // inside a #EXT-X-CUE-OUT/IN or SCTE-35 marked ad break
        int extinfLine;
        int uriLine;
        boolean removed;
    }

    static final class Result {
        final String text;
        final int removedSegments;

        Result(String text, int removedSegments) {
            this.text = text;
            this.removedSegments = removedSegments;
        }
    }

    private M3u8AdFilter() {}

    static Result filter(String m3u8, String playlistHost, AdFilterRules rules) {
        if (m3u8 == null || m3u8.isEmpty()) return new Result(m3u8 == null ? "" : m3u8, 0);
        List<String> lines = splitLines(m3u8);
        if (lines.isEmpty() || !lines.get(0).trim().startsWith("#EXTM3U")) {
            return new Result(m3u8, 0);
        }
        if (isMasterPlaylist(lines)) return new Result(m3u8, 0);

        List<Segment> segments = parseSegments(lines);
        if (segments.isEmpty()) return new Result(m3u8, 0);

        boolean encrypted = isEncrypted(segments);

        // Rule-based and SCTE/CUE removal are only safe for clear streams: deleting a segment in
        // the middle of an AES-128 stream shifts the implicit IV of every following segment and
        // breaks decryption.
        if (!encrypted) {
            for (Segment segment : segments) {
                if (segment.scteAd || rules.matchesSegment(playlistHost, segment.uri)) {
                    segment.removed = true;
                }
            }
        }

        if (rules.heuristics != null && rules.heuristics.enabled) {
            Set<Integer> heuristic =
                    HeuristicAdDetector.detect(segments, rules.heuristics, encrypted);
            for (Integer index : heuristic) {
                if (index >= 0 && index < segments.size()) segments.get(index).removed = true;
            }
        }

        int removedCount = 0;
        for (Segment segment : segments) {
            if (segment.removed) removedCount++;
        }
        if (removedCount == 0) return new Result(m3u8, 0);

        return new Result(rebuild(lines, segments, removedCount), removedCount);
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                int end = i;
                if (end > start && text.charAt(end - 1) == '\r') end--;
                lines.add(text.substring(start, end));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            int end = text.length();
            if (end > start && text.charAt(end - 1) == '\r') end--;
            lines.add(text.substring(start, end));
        }
        return lines;
    }

    private static boolean isMasterPlaylist(List<String> lines) {
        for (String line : lines) {
            if (line.trim().startsWith("#EXT-X-STREAM-INF")) return true;
        }
        return false;
    }

    private static List<Segment> parseSegments(List<String> lines) {
        List<Segment> segments = new ArrayList<>();
        String currentKey = null;
        int pendingDiscontinuity = 0;
        boolean inScteAd = false;
        for (int i = 1; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("#EXT-X-KEY:")) {
                currentKey = parseKeyMethod(trimmed);
                continue;
            }
            if (trimmed.startsWith("#EXT-X-DISCONTINUITY")) {
                pendingDiscontinuity++;
                continue;
            }
            if (isScteAdStart(trimmed)) {
                inScteAd = true;
                continue;
            }
            if (trimmed.startsWith("#EXT-X-CUE-IN")) {
                inScteAd = false;
                continue;
            }
            if (!trimmed.startsWith("#EXTINF:")) continue;
            int uriLine = -1;
            for (int j = i + 1; j < lines.size(); j++) {
                String next = lines.get(j).trim();
                if (next.isEmpty() || next.startsWith("#")) continue;
                uriLine = j;
                break;
            }
            if (uriLine == -1) continue;
            Segment segment = new Segment();
            segment.extinfLine = i;
            segment.uriLine = uriLine;
            segment.uri = lines.get(uriLine).trim();
            segment.durationSec = parseDuration(trimmed);
            segment.keyMethod = currentKey;
            segment.discontinuityBefore = pendingDiscontinuity;
            segment.scteAd = inScteAd;
            pendingDiscontinuity = 0;
            segments.add(segment);
            i = uriLine;
        }
        return segments;
    }

    /** Industry-standard ad-break markers: CUE-OUT/IN and the SCTE-35 family. */
    private static boolean isScteAdStart(String line) {
        if (line.startsWith("#EXT-X-CUE-OUT")
                || line.startsWith("#EXT-X-SCTE35")
                || line.startsWith("#EXT-OATCLS-SCTE35")) {
            return true;
        }
        return line.startsWith("#EXT-X-DATERANGE")
                && line.toUpperCase(java.util.Locale.ROOT).contains("SCTE35");
    }

    private static String parseKeyMethod(String keyLine) {
        int index = keyLine.indexOf("METHOD=");
        if (index == -1) return null;
        int start = index + "METHOD=".length();
        int end = start;
        while (end < keyLine.length() && keyLine.charAt(end) != ',' && keyLine.charAt(end) != ' ') {
            end++;
        }
        String method = keyLine.substring(start, end).trim();
        return method.isEmpty() ? null : method;
    }

    private static float parseDuration(String extinfLine) {
        String body = extinfLine.substring("#EXTINF:".length()).trim();
        int comma = body.indexOf(',');
        String number = comma >= 0 ? body.substring(0, comma) : body;
        number = number.trim();
        try {
            return Float.parseFloat(number);
        } catch (NumberFormatException ignored) {
            return 0f;
        }
    }

    private static boolean isEncrypted(List<Segment> segments) {
        for (Segment segment : segments) {
            if (segment.keyMethod != null && !"NONE".equals(segment.keyMethod)) return true;
        }
        return false;
    }

    private static String rebuild(List<String> lines, List<Segment> segments, int removedCount) {
        boolean[] drop = new boolean[lines.size()];
        for (Segment segment : segments) {
            if (segment.removed) {
                drop[segment.extinfLine] = true;
                drop[segment.uriLine] = true;
            }
        }

        int removedAtStart = 0;
        for (Segment segment : segments) {
            if (segment.removed) {
                removedAtStart++;
            } else {
                break;
            }
        }

        StringBuilder out = new StringBuilder();
        boolean sequenceAdjusted = false;
        for (int i = 0; i < lines.size(); i++) {
            if (drop[i]) continue;
            String line = lines.get(i);
            if (removedAtStart > 0 && !sequenceAdjusted) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try {
                        long sequence =
                                Long.parseLong(
                                        trimmed.substring("#EXT-X-MEDIA-SEQUENCE:".length())
                                                .trim());
                        line = "#EXT-X-MEDIA-SEQUENCE:" + (sequence + removedAtStart);
                    } catch (NumberFormatException ignored) {
                    }
                    sequenceAdjusted = true;
                }
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
