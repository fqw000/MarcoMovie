import 'package:flutter_test/flutter_test.dart';

import 'package:marco_movie/core/ad_filter_rules.dart';
import 'package:marco_movie/core/m3u8_ad_filter.dart';
import 'package:marco_movie/core/rules_importer.dart';

void main() {
  test('master playlist passthrough', () {
    final master =
        '#EXTM3U\n' + '#EXT-X-STREAM-INF:BANDWIDTH=800000\n' + 'variant.m3u8\n';
    final result = M3u8AdFilter.filter(master, 'host', AdFilterRules.empty());
    expect(result.text, master);
    expect(result.removedSegments, 0);
  });

  test('rule removal drops ad segments and keeps content', () {
    final playlist = '#EXTM3U\n'
        '#EXT-X-TARGETDURATION:10\n'
        '#EXT-X-MEDIA-SEQUENCE:0\n'
        '#EXTINF:10.0,\n'
        'seg0.ts\n'
        '#EXTINF:5.0,\n'
        'ad_seg1.ts\n'
        '#EXTINF:5.0,\n'
        'ad_seg2.ts\n'
        '#EXTINF:10.0,\n'
        'seg1.ts\n';
    final rules = AdFilterRules.parse(
        '{"playlistRules":[{"type":"keyword","pattern":"ad_seg"}]}');
    final result = M3u8AdFilter.filter(playlist, 'host', rules);
    expect(result.removedSegments, 2);
    expect(result.text.contains('ad_seg1.ts'), isFalse);
    expect(result.text.contains('ad_seg2.ts'), isFalse);
    expect(result.text.contains('seg0.ts'), isTrue);
    expect(result.text.contains('seg1.ts'), isTrue);
  });

  test('leading removal bumps media sequence', () {
    final playlist = '#EXTM3U\n'
        '#EXT-X-MEDIA-SEQUENCE:100\n'
        '#EXTINF:5.0,\n'
        'ad_1.ts\n'
        '#EXTINF:10.0,\n'
        'content.ts\n';
    final rules = AdFilterRules.parse(
        '{"playlistRules":[{"type":"keyword","pattern":"ad_"}]}');
    final result = M3u8AdFilter.filter(playlist, 'host', rules);
    expect(result.removedSegments, 1);
    expect(result.text.contains('#EXT-X-MEDIA-SEQUENCE:101'), isTrue);
    expect(result.text.contains('ad_1.ts'), isFalse);
    expect(result.text.contains('content.ts'), isTrue);
  });

  test('encrypted stream skips rule-based removal', () {
    final playlist = '#EXTM3U\n'
        '#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n'
        '#EXTINF:10.0,\n'
        'enc0.ts\n'
        '#EXTINF:5.0,\n'
        'ad_seg.ts\n'
        '#EXTINF:10.0,\n'
        'enc1.ts\n';
    final rules = AdFilterRules.parse(
        '{"playlistRules":[{"type":"keyword","pattern":"ad_seg"}]}');
    final result = M3u8AdFilter.filter(playlist, 'host', rules);
    expect(result.removedSegments, 0);
  });

  test('SCTE/CUE-OUT removes marked ad block', () {
    final playlist = '#EXTM3U\n'
        '#EXTINF:10.0,\n'
        'seg0.ts\n'
        '#EXT-X-CUE-OUT:DURATION=15\n'
        '#EXTINF:5.0,\n'
        'ad1.ts\n'
        '#EXTINF:5.0,\n'
        'ad2.ts\n'
        '#EXT-X-CUE-IN\n'
        '#EXTINF:10.0,\n'
        'seg1.ts\n';
    final result = M3u8AdFilter.filter(playlist, 'host', AdFilterRules.empty());
    expect(result.removedSegments, 2);
    expect(result.text.contains('ad1.ts'), isFalse);
    expect(result.text.contains('ad2.ts'), isFalse);
    expect(result.text.contains('seg0.ts'), isTrue);
    expect(result.text.contains('seg1.ts'), isTrue);
  });

  test('default heuristics leave variable-duration content intact', () {
    final buffer = StringBuffer('#EXTM3U\n');
    for (var i = 0; i < 50; i++) {
      buffer.write('#EXTINF:10.0,\n');
      buffer.write('s$i.ts\n');
    }
    buffer.write('#EXTINF:90.0,\n');
    buffer.write('long.ts\n');
    for (var i = 50; i < 100; i++) {
      buffer.write('#EXTINF:10.0,\n');
      buffer.write('s$i.ts\n');
    }
    final rules = AdFilterRules.parse('{"heuristics":{"enabled":true}}');
    final result = M3u8AdFilter.filter(buffer.toString(), 'host', rules);
    expect(result.removedSegments, 0);
  });

  test('opt-in duration window removes bounded short block', () {
    final playlist = '#EXTM3U\n'
        '#EXTINF:40.0,\n'
        'a.ts\n'
        '#EXTINF:5.0,\n'
        'ad1.ts\n'
        '#EXTINF:5.0,\n'
        'ad2.ts\n'
        '#EXTINF:40.0,\n'
        'b.ts\n';
    final rules = AdFilterRules.parse(
        '{"heuristics":{"enabled":true,"durationWindows":true,'
        '"minAdBlockSegments":2}}');
    final result = M3u8AdFilter.filter(playlist, 'host', rules);
    expect(result.removedSegments, 2);
    expect(result.text.contains('ad1.ts'), isFalse);
    expect(result.text.contains('ad2.ts'), isFalse);
  });

  test('key-none switch removes clear block on encrypted stream', () {
    final playlist = '#EXTM3U\n'
        '#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n'
        '#EXTINF:10.0,\n'
        'enc0.ts\n'
        '#EXT-X-KEY:METHOD=NONE\n'
        '#EXTINF:5.0,\n'
        'clear1.ts\n'
        '#EXTINF:5.0,\n'
        'clear2.ts\n'
        '#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n'
        '#EXTINF:10.0,\n'
        'enc1.ts\n';
    final rules = AdFilterRules.parse(
        '{"heuristics":{"enabled":true,"minAdBlockSegments":2,"keyNoneSwitch":true}}');
    final result = M3u8AdFilter.filter(playlist, 'host', rules);
    expect(result.removedSegments, 2);
    expect(result.text.contains('clear1.ts'), isFalse);
    expect(result.text.contains('clear2.ts'), isFalse);
  });

  test('mirror reuse removes repeated ad-loop URI', () {
    final playlist = '#EXTM3U\n'
        '#EXTINF:5.0,\n'
        'loop.ts\n'
        '#EXTINF:5.0,\n'
        'loop.ts\n'
        '#EXTINF:5.0,\n'
        'loop.ts\n'
        '#EXTINF:10.0,\n'
        'c1.ts\n'
        '#EXTINF:10.0,\n'
        'c2.ts\n'
        '#EXTINF:10.0,\n'
        'c3.ts\n';
    final rules = AdFilterRules.parse(
        '{"heuristics":{"enabled":true,"mirrorReuse":true}}');
    final result = M3u8AdFilter.filter(playlist, 'host', rules);
    expect(result.removedSegments, 3);
    expect(result.text.contains('c1.ts'), isTrue);
    expect(result.text.contains('c2.ts'), isTrue);
  });

  test('url prefix anomaly removes off-prefix block', () {
    final playlist = '#EXTM3U\n'
        '#EXTINF:10.0,\n'
        'https://cdn.test/a/0.ts\n'
        '#EXTINF:5.0,\n'
        'https://ads.test/ad/1.ts\n'
        '#EXTINF:5.0,\n'
        'https://ads.test/ad/2.ts\n'
        '#EXTINF:10.0,\n'
        'https://cdn.test/a/1.ts\n';
    final rules = AdFilterRules.parse(
        '{"heuristics":{"enabled":true,"minAdBlockSegments":2,"urlPrefixAnomaly":true}}');
    final result = M3u8AdFilter.filter(playlist, 'host', rules);
    expect(result.removedSegments, 2);
    expect(result.text.contains('cdn.test'), isTrue);
  });

  test('XC.json maps host-scoped regex rules', () {
    final xc =
        '[{"name":"site","host":"example.com","filter":"/ad/\\nsponsor"}]';
    final xcConverted = RulesImporter.fromXcJson(xc);
    expect(xcConverted, isNotNull);
    expect(xcConverted, contains('example.com'));
    final xcRules = AdFilterRules.parse(xcConverted!);
    expect(xcRules.hostRules.length, 1);
    expect(xcRules.matchesSegment('example.com', 'x/ad/y.ts'), isTrue);
  });

  test('anti-AD hosts list maps to blocked domains', () {
    final antiAd = '0.0.0.0 doubleclick.net\nads.example.com\n';
    final antiAdConverted = RulesImporter.fromAntiAd(antiAd);
    expect(antiAdConverted, isNotNull);
    final antiRules = AdFilterRules.parse(antiAdConverted!);
    expect(antiRules.isBlockedHost('doubleclick.net'), isTrue);
    expect(antiRules.isBlockedHost('ads.example.com'), isTrue);
  });

  test('m3u8-proxy maps strings to global regex', () {
    final proxy = '{"rules":["/ad/","promo"]}';
    final proxyConverted = RulesImporter.fromM3u8Proxy(proxy);
    expect(proxyConverted, isNotNull);
    final proxyRules = AdFilterRules.parse(proxyConverted!);
    expect(proxyRules.globalRegex.length, 2);
  });

  test('merge combines host rules and domains', () {
    final xc =
        '[{"name":"site","host":"example.com","filter":"/ad/\\nsponsor"}]';
    final xcConverted = RulesImporter.fromXcJson(xc);
    final antiAd = '0.0.0.0 doubleclick.net\nads.example.com\n';
    final antiAdConverted = RulesImporter.fromAntiAd(antiAd);

    final merged = RulesImporter.merge([xcConverted, antiAdConverted]);
    expect(merged, isNotNull);
    final mergedRules = AdFilterRules.parse(merged!);
    expect(mergedRules.hostRules.length, 1);
    expect(mergedRules.blockedDomains.length, 2);
  });

  test('AdFilterRules.merge combines builtin and custom', () {
    final base = AdFilterRules.parse(
        '{"playlistRules":[{"type":"keyword","pattern":"base_ad"}],'
        '"heuristics":{"enabled":true,"keyNoneSwitch":true}}');
    final customNoHeu = AdFilterRules.parse(
        '{"playlistRules":[{"type":"keyword","pattern":"custom_ad"}]}');
    final merged = AdFilterRules.merge(base, customNoHeu);
    expect(merged.globalKeywords.length, 2);
    expect(merged.heuristics.enabled, isTrue);
    expect(merged.heuristics.keyNoneSwitch, isTrue);

    final customHeu = AdFilterRules.parse('{"heuristics":{"enabled":false}}');
    final merged2 = AdFilterRules.merge(base, customHeu);
    expect(merged2.heuristics.enabled, isFalse);
  });
}
