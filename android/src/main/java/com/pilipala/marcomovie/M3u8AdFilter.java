package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java HLS media-playlist filter, aligned with the validated {@code m3u8-adfilter} reference
 * kernel. It parses the playlist into segment nodes, applies structural gates (master / live /
 * implicit-IV), marks strong ad evidence (SCTE-35/CUE, URL rules, private comment markers, KEY-NONE
 * clear blocks), then scores discontinuity-bounded chunks. Weak shape signals must be corroborated
 * by a structural signal before they may remove anything. The playlist is rebuilt with structural
 * conservation (KEY continuity, seam DISCONTINUITY merge, CUE pairing) and self-verified; any
 * invariant violation rolls the whole edit back.
 *
 * <p>This class has no Android or Media3 dependency so it can be exercised by the offline JVM
 * checks under {@code android/tests}.
 */
final class M3u8AdFilter {

    /** Private comment markers left by ad-stitching systems, e.g. {@code # ppvod-ad-injected v1}. */
    private static final java.util.regex.Pattern AD_COMMENT =
            java.util.regex.Pattern.compile(
                    "(?i)(ad[-_]?(inject|insert|mark|marker)|ppvod|guanggao|adseg|插播|广告)");

    static final class Node {
        int index;
        String extinf;
        String uri;
        float dur;
        String keyMethod; // null = no KEY seen, "NONE", or e.g. "AES-128"
        String keyRaw; // raw #EXT-X-KEY line, for structural conservation
        boolean hasDisc; // #EXT-X-DISCONTINUITY seen before this node
        boolean scte; // inside a CUE-OUT/IN or SCTE-35 marked ad break
        boolean adComment; // private ad-injection comment seen before this node
        List<String> tags; // other tag lines (CUE, DATERANGE, comments) kept for rebuild
        boolean ruleHit; // segment URI matched by rules
        boolean keynone; // clear segment inside an otherwise encrypted stream
        boolean namingReuse; // naming-key reuse evidence
        boolean mirrorReuse; // exact URI reuse evidence
        String reason; // human-readable evidence, for diagnostics
        boolean dropped;
    }

    static final class Result {
        final String text;
        final int removedSegments;
        final String reason;

        Result(String text, int removedSegments, String reason) {
            this.text = text;
            this.removedSegments = removedSegments;
            this.reason = reason;
        }
    }

    private static final class Parsed {
        List<String> head;
        List<Node> nodes;
        List<String> tail;
        boolean implicitIv;
        boolean encrypted;

        Parsed(
                List<String> head,
                List<Node> nodes,
                List<String> tail,
                boolean implicitIv,
                boolean encrypted) {
            this.head = head;
            this.nodes = nodes;
            this.tail = tail;
            this.implicitIv = implicitIv;
            this.encrypted = encrypted;
        }
    }

    private M3u8AdFilter() {}

    static Result filter(String m3u8, String playlistHost, AdFilterRules rules) {
        if (m3u8 == null || m3u8.trim().isEmpty()) {
            return new Result(m3u8 == null ? "" : m3u8, 0, "empty");
        }
        List<String> lines = splitLines(m3u8);
        if (lines.isEmpty() || !lines.get(0).trim().startsWith("#EXTM3U")) {
            return new Result(m3u8, 0, "not-m3u8");
        }

        Parsed parsed = parse(lines);
        if (parsed.head.isEmpty() && parsed.nodes.isEmpty())
            return new Result(m3u8, 0, "no-content");

        // Master playlists are never edited; the variant media playlist is filtered instead.
        if (isMaster(parsed.head)) return new Result(m3u8, 0, "master");

        // Live / EVENT playlists have a sliding window; deleting segments breaks sequence math.
        if (!hasEndList(parsed.tail) && !containsLine(m3u8, "#EXT-X-ENDLIST")) {
            return new Result(m3u8, 0, "live-no-endlist");
        }

        // AES-128 / SAMPLE-AES without an explicit IV derives each IV from the segment sequence
        // number, so removing any segment shifts every following IV and breaks decryption. The only
        // safe action is to leave the whole playlist untouched.
        if (parsed.implicitIv) return new Result(m3u8, 0, "aes-implicit-iv");

        if (parsed.nodes.isEmpty()) return new Result(m3u8, 0, "no-segments");

        AdFilterRules.Heuristics heu = rules == null ? null : rules.heuristics;

        // Reliable per-segment evidence: URL rules and SCTE/CUE markers. These are unambiguous and
        // always run, independent of the heuristic switch. Safe on clear and explicit-IV streams
        // (implicit-IV was gated out above).
        for (Node node : parsed.nodes) {
            if (rules != null && rules.matchesSegment(playlistHost, node.uri)) {
                node.ruleHit = true;
                node.dropped = true;
                node.reason = "rule";
            }
            if (node.scte) {
                node.dropped = true;
                node.reason = first(node.reason, "scte");
            }
        }

        // Private comment markers (AD-02) mark the whole discontinuity-bounded block, e.g.
        // "# ppvod-ad-injected v1" immediately before a DISCONTINUITY-bounded ad pod.
        for (int i = 0; i < parsed.nodes.size(); i++) {
            Node marker = parsed.nodes.get(i);
            if (!marker.adComment) continue;
            int j = i;
            while (j < parsed.nodes.size()) {
                if (j > i && parsed.nodes.get(j).hasDisc) break; // closing boundary
                Node block = parsed.nodes.get(j);
                block.dropped = true;
                if (block.reason == null) block.reason = "ad-comment";
                j++;
            }
        }

        // Heuristic chunk detection (KEY-NONE clear blocks + discontinuity-bounded shape scoring).
        if (heu == null || heu.enabled) {
            Set<Integer> dropped =
                    HeuristicAdDetector.detect(parsed.nodes, heu, playlistHost, parsed.encrypted);
            for (int i : dropped) {
                Node n = parsed.nodes.get(i);
                n.dropped = true;
                if (n.reason == null) n.reason = "heuristic";
            }
        }

        int removed = 0;
        for (Node n : parsed.nodes) if (n.dropped) removed++;
        if (removed == 0) return new Result(m3u8, 0, "clean");

        // Safety cap: a single small block is always allowed, but a large deletion is suspicious.
        int maxBlock = heu == null ? 8 : heu.maxAdBlockSegments;
        float removedDur = 0f;
        for (Node n : parsed.nodes) if (n.dropped) removedDur += n.dur;
        float ratio = parsed.nodes.isEmpty() ? 0f : (float) removed / parsed.nodes.size();
        if (removed > maxBlock && (ratio > 0.15f || removedDur > 120f)) {
            return new Result(m3u8, 0, "revert-over-cap");
        }

        String rebuilt = rebuild(parsed);
        String verified = verifyAndFallback(m3u8, rebuilt, parsed.nodes.size() - removed);
        if (verified == null) return new Result(m3u8, 0, "revert-invariant");
        return new Result(verified, removed, "changed");
    }

    private static String first(String a, String b) {
        return a == null ? b : a + "+" + b;
    }

    /* ------------------------------------------------------------------ parsing */

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
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

    private static boolean isMaster(List<String> head) {
        for (String line : head) if (line.trim().startsWith("#EXT-X-STREAM-INF")) return true;
        return false;
    }

    private static boolean hasEndList(List<String> tail) {
        for (String line : tail) if (line.trim().startsWith("#EXT-X-ENDLIST")) return true;
        return false;
    }

    private static boolean containsLine(String text, String needle) {
        return text.indexOf(needle) >= 0;
    }

    private static Parsed parse(List<String> lines) {
        List<String> head = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        List<String> tail = new ArrayList<>();

        List<String> pending = new ArrayList<>();
        String currentKeyRaw = null;
        String currentKeyMethod = null;
        boolean implicitIv = false;
        boolean encrypted = false;
        boolean inScte = false;
        boolean pendingAdComment = false;
        String pendingExtinf = null;
        boolean seenSegment = false;

        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;

            if (t.startsWith("#EXTINF:")) {
                pendingExtinf = t;
                continue;
            }

            if (!t.startsWith("#")) {
                if (pendingExtinf != null) {
                    Node node = new Node();
                    node.index = nodes.size();
                    node.extinf = pendingExtinf;
                    node.uri = t;
                    node.dur = parseDuration(pendingExtinf);
                    node.keyRaw = currentKeyRaw;
                    node.keyMethod = currentKeyMethod;
                    node.scte = inScte;
                    node.adComment = pendingAdComment;
                    node.tags = new ArrayList<>(pending);
                    pending.clear();
                    pendingAdComment = false;
                    pendingExtinf = null;
                    seenSegment = true;
                    nodes.add(node);
                } else {
                    head.addAll(pending);
                    head.add(t);
                    pending.clear();
                }
                continue;
            }

            if (!seenSegment && pendingExtinf == null && isGlobalHead(t)) {
                head.add(t);
                continue;
            }
            if (t.startsWith("#EXT-X-KEY:")) {
                currentKeyRaw = t;
                currentKeyMethod = parseKeyMethod(t);
                if ((isAes(currentKeyMethod)) && !hasIv(t)) implicitIv = true;
                if (currentKeyMethod != null && !"NONE".equals(currentKeyMethod)) encrypted = true;
                continue;
            }
            if (t.startsWith("#EXT-X-DISCONTINUITY")) {
                pending.add(t);
                continue;
            }
            if (isScteStart(t)) {
                inScte = true;
                pending.add(t);
                continue;
            }
            if (t.startsWith("#EXT-X-CUE-IN")) {
                inScte = false;
                pending.add(t);
                continue;
            }
            // Private comment markers (AD-02): "# ppvod-ad-injected v1" etc.
            if (t.charAt(0) == '#' && !t.startsWith("#EXT") && AD_COMMENT.matcher(t).find()) {
                pendingAdComment = true;
                pending.add(t);
                continue;
            }
            pending.add(t);
        }
        tail.addAll(pending);

        for (Node node : nodes) {
            node.hasDisc = hasDiscontinuity(node.tags);
        }
        return new Parsed(head, nodes, tail, implicitIv, encrypted);
    }

    private static boolean hasDiscontinuity(List<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith("#EXT-X-DISCONTINUITY")) return true;
        }
        return false;
    }

    private static boolean isGlobalHead(String t) {
        return t.startsWith("#EXTM3U")
                || t.startsWith("#EXT-X-VERSION")
                || t.startsWith("#EXT-X-TARGETDURATION")
                || t.startsWith("#EXT-X-MEDIA-SEQUENCE")
                || t.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")
                || t.startsWith("#EXT-X-PLAYLIST-TYPE")
                || t.startsWith("#EXT-X-I-FRAMES-ONLY")
                || t.startsWith("#EXT-X-INDEPENDENT-SEGMENTS")
                || t.startsWith("#EXT-X-START")
                || t.startsWith("#EXT-X-MAP")
                || t.startsWith("#EXT-X-ALLOW-CACHE");
    }

    private static boolean isAes(String method) {
        return method != null && (method.startsWith("AES-128") || method.startsWith("SAMPLE-AES"));
    }

    private static boolean hasIv(String keyLine) {
        return keyLine.matches("(?s).*,IV=.*");
    }

    private static boolean isScteStart(String line) {
        if (line.startsWith("#EXT-X-CUE-OUT")
                || line.startsWith("#EXT-X-SCTE35")
                || line.startsWith("#EXT-OATCLS-SCTE35")) {
            return true;
        }
        return line.startsWith("#EXT-X-DATERANGE")
                && line.toUpperCase(Locale.ROOT).contains("SCTE35");
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

    /* ------------------------------------------------------------------ rebuild */

    private static String rebuild(Parsed parsed) {
        List<Node> nodes = parsed.nodes;
        List<String> head = new ArrayList<>(parsed.head);

        int droppedAtStart = 0;
        int discAtStart = 0;
        for (Node n : nodes) {
            if (!n.dropped) break;
            droppedAtStart++;
            if (n.hasDisc) discAtStart++;
        }

        for (int i = 0; i < head.size(); i++) {
            String line = head.get(i).trim();
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:") && droppedAtStart > 0) {
                try {
                    long v =
                            Long.parseLong(
                                    line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                    head.set(i, "#EXT-X-MEDIA-SEQUENCE:" + (v + droppedAtStart));
                } catch (NumberFormatException ignored) {
                }
            } else if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") && discAtStart > 0) {
                try {
                    long v =
                            Long.parseLong(
                                    line.substring("#EXT-X-DISCONTINUITY-SEQUENCE:".length())
                                            .trim());
                    head.set(i, "#EXT-X-DISCONTINUITY-SEQUENCE:" + (v + discAtStart));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        List<String> out = new ArrayList<>(head);
        String emittedKey = null;
        int prevKept = -1;
        boolean discEmitted = false;

        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (n.dropped) continue;

            if (n.keyRaw != null && !n.keyRaw.equals(emittedKey)) {
                out.add(n.keyRaw);
                emittedKey = n.keyRaw;
            }

            boolean gapDropped = hasDroppedIn(nodes, prevKept + 1, i - 1);
            boolean wantDisc =
                    n.hasDisc || (gapDropped && rangeHasDisc(nodes, prevKept + 1, i - 1));
            if (wantDisc && !discEmitted) out.add("#EXT-X-DISCONTINUITY");
            discEmitted = wantDisc;

            for (String tag : n.tags) {
                if (tag.startsWith("#EXT-X-KEY:")) continue;
                if (tag.startsWith("#EXT-X-DISCONTINUITY")) continue;
                out.add(tag);
            }
            out.add(n.extinf);
            out.add(n.uri);
            prevKept = i;
        }
        out.addAll(parsed.tail);

        return balanceCues(out);
    }

    private static boolean hasDroppedIn(List<Node> nodes, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (i >= 0 && i < nodes.size() && nodes.get(i).dropped) return true;
        }
        return false;
    }

    private static boolean rangeHasDisc(List<Node> nodes, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (i >= 0 && i < nodes.size() && nodes.get(i).hasDisc) return true;
        }
        return false;
    }

    /** Drops orphaned CUE-IN / CUE-OUT left behind after the ad block is removed. */
    private static String balanceCues(List<String> lines) {
        StringBuilder out = new StringBuilder();
        boolean open = false;
        for (String line : lines) {
            String t = line.trim();
            boolean cueStart = isCueStart(t);
            boolean cueIn = t.startsWith("#EXT-X-CUE-IN");
            if (cueStart) {
                if (open) out.setLength(out.lastIndexOf("#EXT-X-CUE-OUT"));
                open = true;
            } else if (cueIn) {
                if (!open) continue;
                open = false;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean isCueStart(String t) {
        return t.startsWith("#EXT-X-CUE-OUT")
                || t.startsWith("#EXT-X-SCTE35")
                || t.startsWith("#EXT-OATCLS-SCTE35");
    }

    /* ------------------------------------------------------------------ verify */

    private static String verifyAndFallback(String original, String rebuilt, int expectedSegments) {
        Parsed parsed = parse(splitLines(rebuilt));
        int segments = parsed.nodes.size();
        if (segments != expectedSegments) return null;
        if (segments < 1) return null;
        if (!rebuilt.trim().startsWith("#EXTM3U")) return null;
        // No two consecutive DISCONTINUITY markers.
        if (rebuilt.contains("#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY")) return null;
        return rebuilt;
    }
}
