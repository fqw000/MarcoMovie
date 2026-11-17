package com.pilipala.marcomovie;

import java.util.Arrays;

/** Offline JVM checks for the pure-Java ad-filter engine. */
public final class AdFilterChecks {
    static int checks;

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS " + label);
        checks++;
    }

    public static void main(String[] args) {
        checkMasterPassthrough();
        checkRuleRemoval();
        checkMediaSequenceAdjust();
        checkEncryptedStreamSafety();
        checkScteMarkerRemoval();
        checkNoOverMatchByDefault();
        checkDurationWindowHeuristic();
        checkKeyNoneSwitchHeuristic();
        checkMirrorReuseHeuristic();
        checkUrlPrefixHeuristic();
        checkRulesImporters();
        checkMerge();
        System.out.println("AdFilterChecks: " + checks + " checks passed");
    }

    private static void checkMasterPassthrough() {
        String master = "#EXTM3U\n" + "#EXT-X-STREAM-INF:BANDWIDTH=800000\n" + "variant.m3u8\n";
        M3u8AdFilter.Result result = M3u8AdFilter.filter(master, "host", AdFilterRules.empty());
        check(
                result.text.equals(master) && result.removedSegments == 0,
                "master playlist passthrough");
    }

    private static void checkRuleRemoval() {
        String playlist =
                "#EXTM3U\n"
                        + "#EXT-X-TARGETDURATION:10\n"
                        + "#EXT-X-MEDIA-SEQUENCE:0\n"
                        + "#EXTINF:10.0,\n"
                        + "seg0.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "ad_seg1.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "ad_seg2.ts\n"
                        + "#EXTINF:10.0,\n"
                        + "seg1.ts\n";
        AdFilterRules rules =
                AdFilterRules.parse(
                        "{\"playlistRules\":[{\"type\":\"keyword\",\"pattern\":\"ad_seg\"}]}");
        M3u8AdFilter.Result result = M3u8AdFilter.filter(playlist, "host", rules);
        check(result.removedSegments == 2, "rule removal counts two ad segments");
        check(
                !result.text.contains("ad_seg1.ts") && !result.text.contains("ad_seg2.ts"),
                "rule removal drops ad URI lines");
        check(
                result.text.contains("seg0.ts") && result.text.contains("seg1.ts"),
                "rule removal keeps content segments");
    }

    private static void checkMediaSequenceAdjust() {
        String playlist =
                "#EXTM3U\n"
                        + "#EXT-X-MEDIA-SEQUENCE:100\n"
                        + "#EXTINF:5.0,\n"
                        + "ad_1.ts\n"
                        + "#EXTINF:10.0,\n"
                        + "content.ts\n";
        AdFilterRules rules =
                AdFilterRules.parse(
                        "{\"playlistRules\":[{\"type\":\"keyword\",\"pattern\":\"ad_\"}]}");
        M3u8AdFilter.Result result = M3u8AdFilter.filter(playlist, "host", rules);
        check(result.removedSegments == 1, "leading removal counts one");
        check(result.text.contains("#EXT-X-MEDIA-SEQUENCE:101"), "media sequence bumped by one");
        check(
                !result.text.contains("ad_1.ts") && result.text.contains("content.ts"),
                "leading removal drops only ad segment");
    }

    private static void checkEncryptedStreamSafety() {
        String playlist =
                "#EXTM3U\n"
                        + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                        + "#EXTINF:10.0,\n"
                        + "enc0.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "ad_seg.ts\n"
                        + "#EXTINF:10.0,\n"
                        + "enc1.ts\n";
        AdFilterRules rules =
                AdFilterRules.parse(
                        "{\"playlistRules\":[{\"type\":\"keyword\",\"pattern\":\"ad_seg\"}]}");
        M3u8AdFilter.Result result = M3u8AdFilter.filter(playlist, "host", rules);
        check(result.removedSegments == 0, "encrypted stream skips rule-based removal");
    }

    private static void checkScteMarkerRemoval() {
        String playlist =
                "#EXTM3U\n"
                        + "#EXTINF:10.0,\n"
                        + "seg0.ts\n"
                        + "#EXT-X-CUE-OUT:DURATION=15\n"
                        + "#EXTINF:5.0,\n"
                        + "ad1.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "ad2.ts\n"
                        + "#EXT-X-CUE-IN\n"
                        + "#EXTINF:10.0,\n"
                        + "seg1.ts\n";
        M3u8AdFilter.Result result = M3u8AdFilter.filter(playlist, "host", AdFilterRules.empty());
        check(result.removedSegments == 2, "SCTE/CUE-OUT removes marked ad block");
        check(
                !result.text.contains("ad1.ts") && !result.text.contains("ad2.ts"),
                "SCTE/CUE drops ad URI lines");
        check(
                result.text.contains("seg0.ts") && result.text.contains("seg1.ts"),
                "SCTE/CUE keeps content segments");
    }

    /** Regression: variable durations + one long segment must not wipe hundreds of segments. */
    private static void checkNoOverMatchByDefault() {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 50; i++) {
            playlist.append("#EXTINF:10.0,\n").append("s").append(i).append(".ts\n");
        }
        playlist.append("#EXTINF:90.0,\n").append("long.ts\n");
        for (int i = 50; i < 100; i++) {
            playlist.append("#EXTINF:10.0,\n").append("s").append(i).append(".ts\n");
        }
        AdFilterRules rules =
                AdFilterRules.parse("{\"heuristics\":{\"enabled\":true}}");
        M3u8AdFilter.Result result = M3u8AdFilter.filter(playlist.toString(), "host", rules);
        check(result.removedSegments == 0, "default heuristics leave variable-duration content intact");
    }

    private static void checkDurationWindowHeuristic() {
        String playlist =
                "#EXTM3U\n"
                        + "#EXTINF:40.0,\n"
                        + "a.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "ad1.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "ad2.ts\n"
                        + "#EXTINF:40.0,\n"
                        + "b.ts\n";
        AdFilterRules rules =
                AdFilterRules.parse(
                        "{\"heuristics\":{\"enabled\":true,\"durationWindows\":true,"
                                + "\"minAdBlockSegments\":2}}");
        M3u8AdFilter.Result result = M3u8AdFilter.filter(playlist, "host", rules);
        check(result.removedSegments == 2, "opt-in duration window removes bounded short block");
        check(
                !result.text.contains("ad1.ts") && !result.text.contains("ad2.ts"),
                "opt-in duration window drops ad URI lines");
    }

    private static void checkKeyNoneSwitchHeuristic() {
        String playlist =
                "#EXTM3U\n"
                        + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                        + "#EXTINF:10.0,\n"
                        + "enc0.ts\n"
                        + "#EXT-X-KEY:METHOD=NONE\n"
                        + "#EXTINF:5.0,\n"
                        + "clear1.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "clear2.ts\n"
                        + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                        + "#EXTINF:10.0,\n"
                        + "enc1.ts\n";
        AdFilterRules rules =
                AdFilterRules.parse(
                        "{\"heuristics\":{\"enabled\":true,\"minAdBlockSegments\":2,\"keyNoneSwitch\":true}}");
        M3u8AdFilter.Result result = M3u8AdFilter.filter(playlist, "host", rules);
        check(result.removedSegments == 2, "key-none switch removes clear block");
        check(
                !result.text.contains("clear1.ts") && !result.text.contains("clear2.ts"),
                "key-none switch drops clear URI lines");
    }

    private static void checkMirrorReuseHeuristic() {
        String playlist =
                "#EXTM3U\n"
                        + "#EXTINF:5.0,\n"
                        + "loop.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "loop.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "loop.ts\n"
                        + "#EXTINF:10.0,\n"
                        + "c1.ts\n"
                        + "#EXTINF:10.0,\n"
                        + "c2.ts\n"
                        + "#EXTINF:10.0,\n"
                        + "c3.ts\n";
        AdFilterRules rules =
                AdFilterRules.parse(
                        "{\"heuristics\":{\"enabled\":true,\"mirrorReuse\":true}}");
        M3u8AdFilter.Result result = M3u8AdFilter.filter(playlist, "host", rules);
        check(result.removedSegments == 3, "opt-in mirror reuse removes repeated ad-loop URI");
        check(result.text.contains("c1.ts") && result.text.contains("c2.ts"), "mirror reuse keeps unique segments");
    }

    private static void checkUrlPrefixHeuristic() {
        String playlist =
                "#EXTM3U\n"
                        + "#EXTINF:10.0,\n"
                        + "https://cdn.test/a/0.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "https://ads.test/ad/1.ts\n"
                        + "#EXTINF:5.0,\n"
                        + "https://ads.test/ad/2.ts\n"
                        + "#EXTINF:10.0,\n"
                        + "https://cdn.test/a/1.ts\n";
        AdFilterRules rules =
                AdFilterRules.parse(
                        "{\"heuristics\":{\"enabled\":true,\"minAdBlockSegments\":2,\"urlPrefixAnomaly\":true}}");
        M3u8AdFilter.Result result = M3u8AdFilter.filter(playlist, "host", rules);
        check(result.removedSegments == 2, "host anomaly removes off-host ad block");
        check(
                result.text.contains("cdn.test"),
                "host anomaly keeps dominant host segments");
    }

    private static void checkRulesImporters() {
        String xc = "[{\"name\":\"site\",\"host\":\"example.com\",\"filter\":\"/ad/\\nsponsor\"}]";
        String xcConverted = RulesImporter.fromXcJson(xc);
        check(xcConverted != null && xcConverted.contains("example.com"), "XC.json maps host");
        AdFilterRules xcRules = AdFilterRules.parse(xcConverted);
        check(xcRules.hostRules.size() == 1, "XC.json produces one host rule");
        check(xcRules.matchesSegment("example.com", "x/ad/y.ts"), "XC.json host regex matches");

        String antiAd = "0.0.0.0 doubleclick.net\nads.example.com\n";
        String antiAdConverted = RulesImporter.fromAntiAd(antiAd);
        check(antiAdConverted != null, "anti-AD converts");
        AdFilterRules antiRules = AdFilterRules.parse(antiAdConverted);
        check(
                antiRules.isBlockedHost("doubleclick.net")
                        && antiRules.isBlockedHost("ads.example.com"),
                "anti-AD domains blocked");

        String proxy = "{\"rules\":[\"/ad/\",\"promo\"]}";
        String proxyConverted = RulesImporter.fromM3u8Proxy(proxy);
        check(proxyConverted != null, "m3u8-proxy converts");
        AdFilterRules proxyRules = AdFilterRules.parse(proxyConverted);
        check(proxyRules.globalRegex.size() == 2, "m3u8-proxy produces two global regex");

        String merged = RulesImporter.merge(Arrays.asList(xcConverted, antiAdConverted));
        AdFilterRules mergedRules = AdFilterRules.parse(merged);
        check(
                mergedRules.hostRules.size() == 1 && mergedRules.blockedDomains.size() == 2,
                "merge combines host rules and domains");
    }

    private static void checkMerge() {
        AdFilterRules base =
                AdFilterRules.parse(
                        "{\"playlistRules\":[{\"type\":\"keyword\",\"pattern\":\"base_ad\"}],"
                                + "\"heuristics\":{\"enabled\":true,\"keyNoneSwitch\":true}}");
        AdFilterRules customNoHeu =
                AdFilterRules.parse(
                        "{\"playlistRules\":[{\"type\":\"keyword\",\"pattern\":\"custom_ad\"}]}");
        AdFilterRules merged = AdFilterRules.merge(base, customNoHeu);
        check(
                merged.globalKeywords.size() == 2,
                "merge unions builtin and custom keywords");
        check(
                merged.heuristics.enabled && merged.heuristics.keyNoneSwitch,
                "merge keeps builtin heuristics when custom omits them");

        AdFilterRules customHeu =
                AdFilterRules.parse(
                        "{\"heuristics\":{\"enabled\":false}}");
        AdFilterRules merged2 = AdFilterRules.merge(base, customHeu);
        check(
                !merged2.heuristics.enabled,
                "merge uses custom heuristics when explicitly specified");

        AdFilterRules customOnly = AdFilterRules.merge(AdFilterRules.empty(), customNoHeu);
        check(
                customOnly.globalKeywords.size() == 1,
                "merge with empty base keeps custom rules");
    }
}
