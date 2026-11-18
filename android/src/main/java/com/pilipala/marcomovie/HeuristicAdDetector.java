package com.pilipala.marcomovie;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Heuristic ad detection for media playlists, aligned with the validated {@code m3u8-adfilter}
 * reference kernel.
 *
 * <p>Reliable evidence (SCTE/CUE markers, URL rules, private comment markers) is handled directly
 * by {@link M3u8AdFilter}; this class covers two weaker families. First, KEY-NONE clear blocks
 * inside an encrypted stream are a strong signal and are removed outright. Second, segments are
 * grouped into discontinuity-bounded chunks and scored from shape (uniform-short, short-between-
 * long, dense-short) plus structural signals (discontinuity, off-host, naming deviation,
 * naming/mirror reuse, sequence island). Weak shape signals must be corroborated by at least one
 * structural signal before they may remove anything, so ordinary short blocks in content (burned-in
 * captions, slow scenes) are not deleted.
 */
final class HeuristicAdDetector {
    private HeuristicAdDetector() {}

    /* ---- scoring weights (aligned with the reference kernel) ---- */
    private static final double W_UNIFORM_SHORT = 0.40;
    private static final double W_SHORT_ONLY = 0.20;
    private static final double W_DISC = 0.15;
    private static final double W_OFF_HOST = 0.15;
    private static final double W_URL_DEV = 0.10;
    private static final double W_SHORT_BETWEEN_LONG = 0.20;
    private static final double W_NAMING_REUSE = 0.25;
    private static final double W_DENSE_SHORT = 0.10;
    private static final double W_SEQ_ISLAND = 0.08;
    private static final double W_SINGLE = -0.30;
    private static final double W_LONG = -0.20;
    private static final double W_TOTAL = -0.30;

    private static final double BASE_THRESHOLD = 0.60;
    private static final double FIRST_BONUS = 0.20;
    private static final double LAST_BONUS = 0.10;
    private static final double UNIFORM_TOLERANCE = 0.8;

    /** Structural (non-duration) signals required to corroborate a weak shape-only hit. */
    private static final List<String> STRUCTURAL_NOTES =
            Arrays.asList(
                    "disc", "offhost", "url-dev", "naming-reuse", "mirror-reuse", "seq-island");

    static Set<Integer> detect(
            List<M3u8AdFilter.Node> nodes,
            AdFilterRules.Heuristics cfg,
            String playlistHost,
            boolean encrypted) {
        Set<Integer> drop = new HashSet<>();
        if (nodes == null || nodes.isEmpty()) return drop;

        int minSegments = cfg == null ? 2 : cfg.minAdBlockSegments;
        int maxSegments = cfg == null ? 8 : cfg.maxAdBlockSegments;
        int minDur = cfg == null ? 4 : cfg.minAdBlockDurationSec;
        int maxTotal = cfg == null ? 36 : cfg.maxAdBlockTotalSec;
        boolean requireStructural = cfg == null ? true : cfg.requireStructural;
        boolean keyNone = cfg == null ? true : cfg.keyNoneSwitch;

        // KEY-NONE: a clear block inside an otherwise encrypted stream is strong evidence.
        if (encrypted && keyNone) detectKeyNone(nodes, minSegments, maxSegments, drop);

        // Naming / mirror reuse evidence (clear streams only, to avoid key-URI reuse confusion).
        if (!encrypted) detectReuse(nodes, cfg == null ? 30 : cfg.maxAdBlockDurationSec);

        // Discontinuity-bounded chunk scoring.
        List<List<M3u8AdFilter.Node>> chunks = buildChunks(nodes);
        Context ctx = buildContext(nodes, chunks);
        for (int i = 0; i < chunks.size(); i++) {
            List<M3u8AdFilter.Node> chunk = chunks.get(i);
            boolean hit =
                    scoreChunk(
                            chunk,
                            i,
                            chunks,
                            ctx,
                            minSegments,
                            maxSegments,
                            minDur,
                            maxTotal,
                            requireStructural);
            if (hit) {
                for (M3u8AdFilter.Node n : chunk) drop.add(n.index);
            }
        }
        return drop;
    }

    /* ---------------------------------------------------------------- context */

    private static final class Context {
        String dominantHost;
        String dominantKey;
        double medianDur;
        double avgChunkSize;
    }

    private static Context buildContext(
            List<M3u8AdFilter.Node> nodes, List<List<M3u8AdFilter.Node>> chunks) {
        Context ctx = new Context();
        List<String> hosts = new ArrayList<>();
        for (M3u8AdFilter.Node n : nodes) {
            String h = hostOf(n.uri);
            if (h != null) hosts.add(h);
        }
        ctx.dominantHost = hosts.size() >= nodes.size() * 0.5 ? mode(hosts) : null;

        List<String> keys = new ArrayList<>();
        for (M3u8AdFilter.Node n : nodes) {
            String k = namingKey(n.uri);
            if (!k.isEmpty()) keys.add(k);
        }
        ctx.dominantKey = mode(keys);

        List<Float> durs = new ArrayList<>();
        for (M3u8AdFilter.Node n : nodes) if (n.dur > 0f) durs.add(n.dur);
        ctx.medianDur = median(durs);
        ctx.avgChunkSize = chunks.isEmpty() ? nodes.size() : (double) nodes.size() / chunks.size();
        return ctx;
    }

    /* ---------------------------------------------------------------- chunks */

    private static List<List<M3u8AdFilter.Node>> buildChunks(List<M3u8AdFilter.Node> nodes) {
        List<List<M3u8AdFilter.Node>> chunks = new ArrayList<>();
        List<M3u8AdFilter.Node> cur = new ArrayList<>();
        M3u8AdFilter.Node prev = null;
        for (M3u8AdFilter.Node n : nodes) {
            boolean boundary = false;
            if (!cur.isEmpty() && prev != null) {
                if (n.hasDisc) boundary = true;
                else if (!java.util.Objects.equals(n.keyRaw, prev.keyRaw)) boundary = true;
                else if (n.scte != prev.scte) boundary = true;
                else {
                    double jump = Math.abs((double) n.dur - prev.dur);
                    double scale = Math.max(3.0, 0.35 * Math.max(n.dur, prev.dur));
                    if (jump >= scale) boundary = true;
                }
            }
            if (boundary) {
                chunks.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(n);
            prev = n;
        }
        if (!cur.isEmpty()) chunks.add(cur);
        return chunks;
    }

    private static boolean scoreChunk(
            List<M3u8AdFilter.Node> chunk,
            int idx,
            List<List<M3u8AdFilter.Node>> chunks,
            Context ctx,
            int minSegments,
            int maxSegments,
            int minDur,
            int maxTotal,
            boolean requireStructural) {
        int n = chunk.size();
        float total = 0f;
        float min = Float.MAX_VALUE;
        float max = 0f;
        for (M3u8AdFilter.Node node : chunk) {
            total += node.dur;
            if (node.dur < min) min = node.dur;
            if (node.dur > max) max = node.dur;
        }
        boolean uniform = n >= 2 && (max - min) <= UNIFORM_TOLERANCE;
        boolean shortShape = min >= minDur && total <= maxTotal;

        int namingReuseCount = 0, mirrorReuseCount = 0;
        boolean offHost = false, urlDev = false;
        for (M3u8AdFilter.Node node : chunk) {
            if (node.namingReuse) namingReuseCount++;
            if (node.mirrorReuse) mirrorReuseCount++;
            if (ctx.dominantHost != null) {
                String h = hostOf(node.uri);
                if (h != null && !h.equals(ctx.dominantHost)) offHost = true;
            }
            if (ctx.dominantKey != null && !ctx.dominantKey.isEmpty()) {
                if (!namingKey(node.uri).equals(ctx.dominantKey)) urlDev = true;
            }
        }

        List<String> notes = new ArrayList<>();
        double score = 0;
        if (shortShape && uniform) {
            score += W_UNIFORM_SHORT;
            notes.add("uniform-short");
        } else if (shortShape) {
            score += W_SHORT_ONLY;
            notes.add("short");
        }
        if (n < minSegments) {
            score += W_SINGLE;
            notes.add("single");
        }
        if (n > maxSegments) {
            score += W_LONG;
            notes.add("long" + n);
        }
        if (total > 90) {
            score += W_TOTAL;
            notes.add("total>90s");
        }
        if (!chunk.isEmpty() && chunk.get(0).hasDisc) {
            score += W_DISC;
            notes.add("disc");
        }
        if (offHost) {
            score += W_OFF_HOST;
            notes.add("offhost");
        }
        if (urlDev) {
            score += W_URL_DEV;
            notes.add("url-dev");
        }
        if (namingReuseCount * 2 >= n) {
            score += W_NAMING_REUSE;
            notes.add("naming-reuse");
        }
        if (mirrorReuseCount * 2 >= n) {
            score += W_NAMING_REUSE;
            notes.add("mirror-reuse");
        }
        if (idx > 0 && idx < chunks.size() - 1) {
            List<M3u8AdFilter.Node> prevChunk = chunks.get(idx - 1);
            List<M3u8AdFilter.Node> nextChunk = chunks.get(idx + 1);
            if (!prevChunk.isEmpty() && !nextChunk.isEmpty()) {
                float prevDur = prevChunk.get(prevChunk.size() - 1).dur;
                float nextDur = nextChunk.get(0).dur;
                if (prevDur >= 1.5 * max && nextDur >= 1.5 * max) {
                    score += W_SHORT_BETWEEN_LONG;
                    notes.add("short-between-long");
                }
                String curKey = modeKey(chunk);
                String pk = modeKey(prevChunk);
                String nk = modeKey(nextChunk);
                if (!curKey.isEmpty()
                        && !pk.isEmpty()
                        && !nk.isEmpty()
                        && !curKey.equals(pk)
                        && !curKey.equals(nk)) {
                    score += W_SEQ_ISLAND;
                    notes.add("seq-island");
                }
            }
        }
        if (ctx.medianDur > 0
                && ctx.medianDur <= 5
                && uniform
                && n >= 2 * Math.max(1, (int) Math.round(ctx.avgChunkSize))) {
            score += W_DENSE_SHORT;
            notes.add("dense-short");
        }

        double threshold =
                BASE_THRESHOLD
                        + (idx == 0 ? FIRST_BONUS : 0)
                        + (idx == chunks.size() - 1 ? LAST_BONUS : 0);
        boolean hit = score >= threshold;
        if (hit && requireStructural && !hasStructural(notes)) {
            hit = false;
        }
        return hit;
    }

    private static boolean hasStructural(List<String> notes) {
        for (String note : notes) if (STRUCTURAL_NOTES.contains(note)) return true;
        return false;
    }

    private static String modeKey(List<M3u8AdFilter.Node> chunk) {
        List<String> keys = new ArrayList<>();
        for (M3u8AdFilter.Node node : chunk) {
            String k = namingKey(node.uri);
            if (!k.isEmpty()) keys.add(k);
        }
        return mode(keys);
    }

    /* ---------------------------------------------------------------- keynone */

    private static void detectKeyNone(
            List<M3u8AdFilter.Node> nodes, int minSegments, int maxSegments, Set<Integer> drop) {
        int start = 0;
        while (start < nodes.size()) {
            if (!isClear(nodes.get(start))) {
                start++;
                continue;
            }
            int end = start;
            while (end < nodes.size() && isClear(nodes.get(end))) end++;
            int length = end - start;
            boolean left = start > 0 && !isClear(nodes.get(start - 1));
            boolean right = end < nodes.size() && !isClear(nodes.get(end));
            if (length >= minSegments && length <= maxSegments && (left || right)) {
                for (int i = start; i < end; i++) drop.add(nodes.get(i).index);
            }
            start = end;
        }
    }

    private static boolean isClear(M3u8AdFilter.Node n) {
        return n.keyMethod == null || "NONE".equals(n.keyMethod);
    }

    /* ---------------------------------------------------------------- reuse */

    private static void detectReuse(List<M3u8AdFilter.Node> nodes, int maxDur) {
        Map<String, Integer> uriCount = new HashMap<>();
        Map<String, Integer> keyCount = new HashMap<>();
        for (M3u8AdFilter.Node n : nodes) {
            uriCount.put(n.uri, uriCount.getOrDefault(n.uri, 0) + 1);
            String k = namingKey(n.uri);
            if (!k.isEmpty()) keyCount.put(k, keyCount.getOrDefault(k, 0) + 1);
        }
        for (M3u8AdFilter.Node n : nodes) {
            if (uriCount.size() >= 4 && uriCount.getOrDefault(n.uri, 0) >= 3 && n.dur <= maxDur) {
                n.mirrorReuse = true;
            }
            String k = namingKey(n.uri);
            if (!k.isEmpty()
                    && keyCount.size() >= 3
                    && keyCount.getOrDefault(k, 0) >= 3
                    && n.dur <= maxDur) {
                n.namingReuse = true;
            }
        }
    }

    /* ---------------------------------------------------------------- helpers */

    private static String namingKey(String uri) {
        if (uri == null) return "";
        String p = uri;
        int scheme = p.indexOf("://");
        if (scheme >= 0) {
            int hostStart = scheme + 3;
            int slash = p.indexOf('/', hostStart);
            if (slash >= 0) p = p.substring(slash);
        }
        int q = p.indexOf('?');
        int h = p.indexOf('#');
        int cut = p.length();
        if (q >= 0 && q < cut) cut = q;
        if (h >= 0 && h < cut) cut = h;
        p = p.substring(0, cut);
        return p.replaceAll("\\d+", "#").toLowerCase(Locale.ROOT);
    }

    private static String hostOf(String uri) {
        if (uri == null) return null;
        int scheme = uri.indexOf("://");
        if (scheme < 0) return null; // relative URI carries no host
        int start = scheme + 3;
        int end = start;
        while (end < uri.length()) {
            char c = uri.charAt(end);
            if (c == '/' || c == '?' || c == '#' || c == ':') break;
            end++;
        }
        String host = uri.substring(start, end).trim();
        return host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
    }

    private static String mode(List<String> values) {
        Map<String, Integer> counts = new HashMap<>();
        for (String v : values) {
            if (v == null || v.isEmpty()) continue;
            counts.put(v, counts.getOrDefault(v, 0) + 1);
        }
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static double median(List<Float> values) {
        if (values == null || values.isEmpty()) return 0;
        float[] a = new float[values.size()];
        for (int i = 0; i < values.size(); i++) a[i] = values.get(i);
        Arrays.sort(a);
        int m = a.length >> 1;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2.0;
    }
}
