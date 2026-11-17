import 'ad_filter_rules.dart';

/// A parsed media-playlist segment. Mirrors `M3u8AdFilter.Segment`.
class Segment {
  String uri = '';
  double durationSec = 0.0;
  String? keyMethod; // null = no KEY seen, "NONE", or e.g. "AES-128"
  int discontinuityBefore = 0; // #EXT-X-DISCONTINUITY markers since previous segment
  bool scteAd = false; // inside a #EXT-X-CUE-OUT/IN or SCTE-35 marked ad break
  int extinfLine = 0;
  int uriLine = 0;
  bool removed = false;
}

/// Filter result. Mirrors `M3u8AdFilter.Result`.
class M3u8FilterResult {
  final String text;
  final int removedSegments;

  M3u8FilterResult(this.text, this.removedSegments);
}

/// Pure HLS media-playlist filter: removes ad segments matched by
/// [AdFilterRules] and by [HeuristicAdDetector], then rebuilds a structurally
/// valid playlist.
class M3u8AdFilter {
  M3u8AdFilter._();

  static M3u8FilterResult filter(
      String? m3u8, String? playlistHost, AdFilterRules rules) {
    if (m3u8 == null || m3u8.isEmpty) {
      return M3u8FilterResult(m3u8 ?? '', 0);
    }
    final lines = _splitLines(m3u8);
    if (lines.isEmpty || !lines[0].trim().startsWith('#EXTM3U')) {
      return M3u8FilterResult(m3u8, 0);
    }
    if (_isMasterPlaylist(lines)) return M3u8FilterResult(m3u8, 0);

    final segments = _parseSegments(lines);
    if (segments.isEmpty) return M3u8FilterResult(m3u8, 0);

    final encrypted = _isEncrypted(segments);

    // Rule-based and SCTE/CUE removal are only safe for clear streams: deleting
    // a segment in the middle of an AES-128 stream shifts the implicit IV of
    // every following segment and breaks decryption.
    if (!encrypted) {
      for (final segment in segments) {
        if (segment.scteAd || rules.matchesSegment(playlistHost, segment.uri)) {
          segment.removed = true;
        }
      }
    }

    if (rules.heuristics.enabled) {
      final heuristic =
          HeuristicAdDetector.detect(segments, rules.heuristics, encrypted);
      for (final index in heuristic) {
        if (index >= 0 && index < segments.length) {
          segments[index].removed = true;
        }
      }
    }

    var removedCount = 0;
    for (final segment in segments) {
      if (segment.removed) removedCount++;
    }
    if (removedCount == 0) return M3u8FilterResult(m3u8, 0);

    return M3u8FilterResult(
        _rebuild(lines, segments, removedCount), removedCount);
  }

  static List<String> _splitLines(String text) {
    final lines = <String>[];
    var start = 0;
    for (var i = 0; i < text.length; i++) {
      final ch = text.codeUnitAt(i);
      if (ch == 0x0A) {
        var end = i;
        if (end > start && text.codeUnitAt(end - 1) == 0x0D) end--;
        lines.add(text.substring(start, end));
        start = i + 1;
      }
    }
    if (start < text.length) {
      var end = text.length;
      if (end > start && text.codeUnitAt(end - 1) == 0x0D) end--;
      lines.add(text.substring(start, end));
    }
    return lines;
  }

  static bool _isMasterPlaylist(List<String> lines) {
    for (final line in lines) {
      if (line.trim().startsWith('#EXT-X-STREAM-INF')) return true;
    }
    return false;
  }

  static List<Segment> _parseSegments(List<String> lines) {
    final segments = <Segment>[];
    String? currentKey;
    var pendingDiscontinuity = 0;
    var inScteAd = false;
    for (var i = 1; i < lines.length; i++) {
      final trimmed = lines[i].trim();
      if (trimmed.startsWith('#EXT-X-KEY:')) {
        currentKey = _parseKeyMethod(trimmed);
        continue;
      }
      if (trimmed.startsWith('#EXT-X-DISCONTINUITY')) {
        pendingDiscontinuity++;
        continue;
      }
      if (_isScteAdStart(trimmed)) {
        inScteAd = true;
        continue;
      }
      if (trimmed.startsWith('#EXT-X-CUE-IN')) {
        inScteAd = false;
        continue;
      }
      if (!trimmed.startsWith('#EXTINF:')) continue;
      var uriLine = -1;
      for (var j = i + 1; j < lines.length; j++) {
        final next = lines[j].trim();
        if (next.isEmpty || next.startsWith('#')) continue;
        uriLine = j;
        break;
      }
      if (uriLine == -1) continue;
      final segment = Segment();
      segment.extinfLine = i;
      segment.uriLine = uriLine;
      segment.uri = lines[uriLine].trim();
      segment.durationSec = _parseDuration(trimmed);
      segment.keyMethod = currentKey;
      segment.discontinuityBefore = pendingDiscontinuity;
      segment.scteAd = inScteAd;
      pendingDiscontinuity = 0;
      segments.add(segment);
      i = uriLine;
    }
    return segments;
  }

  /// Industry-standard ad-break markers: CUE-OUT/IN and the SCTE-35 family.
  static bool _isScteAdStart(String line) {
    if (line.startsWith('#EXT-X-CUE-OUT') ||
        line.startsWith('#EXT-X-SCTE35') ||
        line.startsWith('#EXT-OATCLS-SCTE35')) {
      return true;
    }
    return line.startsWith('#EXT-X-DATERANGE') &&
        line.toUpperCase().contains('SCTE35');
  }

  static String? _parseKeyMethod(String keyLine) {
    const method = 'METHOD=';
    final index = keyLine.indexOf(method);
    if (index == -1) return null;
    final start = index + method.length;
    var end = start;
    // Stop at the comma or space that terminates the METHOD value.
    while (end < keyLine.length &&
        keyLine.codeUnitAt(end) != 0x2C &&
        keyLine.codeUnitAt(end) != 0x20) {
      end++;
    }
    final value = keyLine.substring(start, end).trim();
    return value.isEmpty ? null : value;
  }

  static double _parseDuration(String extinfLine) {
    final body = extinfLine.substring('#EXTINF:'.length).trim();
    final comma = body.indexOf(',');
    var number = comma >= 0 ? body.substring(0, comma) : body;
    number = number.trim();
    return double.tryParse(number) ?? 0.0;
  }

  static bool _isEncrypted(List<Segment> segments) {
    for (final segment in segments) {
      if (segment.keyMethod != null && segment.keyMethod != 'NONE') return true;
    }
    return false;
  }

  static String _rebuild(
      List<String> lines, List<Segment> segments, int removedCount) {
    final drop = List<bool>.filled(lines.length, false);
    for (final segment in segments) {
      if (segment.removed) {
        drop[segment.extinfLine] = true;
        drop[segment.uriLine] = true;
      }
    }

    var removedAtStart = 0;
    for (final segment in segments) {
      if (segment.removed) {
        removedAtStart++;
      } else {
        break;
      }
    }

    final out = StringBuffer();
    var sequenceAdjusted = false;
    for (var i = 0; i < lines.length; i++) {
      if (drop[i]) continue;
      var line = lines[i];
      if (removedAtStart > 0 && !sequenceAdjusted) {
        final trimmed = line.trim();
        if (trimmed.startsWith('#EXT-X-MEDIA-SEQUENCE:')) {
          final numberText =
              trimmed.substring('#EXT-X-MEDIA-SEQUENCE:'.length).trim();
          final sequence = int.tryParse(numberText);
          if (sequence != null) {
            line = '#EXT-X-MEDIA-SEQUENCE:${sequence + removedAtStart}';
          }
          sequenceAdjusted = true;
        }
      }
      out.write(line);
      out.write('\n');
    }
    return out.toString();
  }
}

/// Heuristic ad-segment detection for media playlists whose ad inserts cannot
/// be described by static regex/keyword rules.
class HeuristicAdDetector {
  HeuristicAdDetector._();

  static Set<int> detect(
      List<Segment> segments, Heuristics cfg, bool encrypted) {
    final remove = <int>{};
    if (!cfg.enabled || segments.isEmpty) return remove;

    // Removing segments inside an encrypted stream shifts implicit IVs, so only
    // the clear-block detector is safe there. The remaining detectors run on
    // clear streams only.
    if (encrypted) {
      if (cfg.keyNoneSwitch) {
        remove.addAll(_detectKeyNoneSwitch(segments, cfg));
      }
      return remove;
    }
    if (cfg.urlPrefixAnomaly) {
      remove.addAll(_detectHostAnomaly(segments, cfg));
    }
    if (cfg.mirrorReuse) {
      remove.addAll(_detectMirrorReuse(segments, cfg));
    }
    if (cfg.durationWindows) {
      remove.addAll(_detectDurationWindows(segments, cfg));
    }
    return remove;
  }

  static bool _isClear(Segment segment) {
    return segment.keyMethod == null || segment.keyMethod == 'NONE';
  }

  /// Removes contiguous clear blocks that sit inside an otherwise encrypted
  /// stream.
  static Set<int> _detectKeyNoneSwitch(
      List<Segment> segments, Heuristics cfg) {
    final remove = <int>{};
    var start = 0;
    while (start < segments.length) {
      if (!_isClear(segments[start])) {
        start++;
        continue;
      }
      var end = start;
      while (end < segments.length && _isClear(segments[end])) {
        end++;
      }
      final length = end - start;
      final boundedLeft = start > 0 && !_isClear(segments[start - 1]);
      final boundedRight = end < segments.length && !_isClear(segments[end]);
      if (length >= cfg.minAdBlockSegments &&
          length <= cfg.maxAdBlockSegments &&
          (boundedLeft || boundedRight)) {
        for (var i = start; i < end; i++) {
          remove.add(i);
        }
      }
      start = end;
    }
    return remove;
  }

  /// Removes short contiguous runs served from a different host than the
  /// playlist's dominant host, only when the run is bounded on both sides by the
  /// dominant host (classic ad-CD insert).
  static Set<int> _detectHostAnomaly(
      List<Segment> segments, Heuristics cfg) {
    final remove = <int>{};
    final dominant = _dominantHost(segments);
    if (dominant == null || dominant.isEmpty) return remove;
    var start = 0;
    while (start < segments.length) {
      if (_hostOf(segments[start].uri) == dominant) {
        start++;
        continue;
      }
      var end = start;
      while (end < segments.length && _hostOf(segments[end].uri) != dominant) {
        end++;
      }
      final length = end - start;
      final boundedLeft =
          start > 0 && _hostOf(segments[start - 1].uri) == dominant;
      final boundedRight =
          end < segments.length && _hostOf(segments[end].uri) == dominant;
      if (length >= cfg.minAdBlockSegments &&
          length <= cfg.maxAdBlockSegments &&
          boundedLeft &&
          boundedRight) {
        for (var i = start; i < end; i++) {
          remove.add(i);
        }
      }
      start = end;
    }
    return remove;
  }

  /// Removes every occurrence of a URI that repeats heavily (ad-loop evidence)
  /// while the rest of the playlist is mostly unique. Requires at least three
  /// repeats, at least four distinct URIs, and short segment durations.
  static Set<int> _detectMirrorReuse(
      List<Segment> segments, Heuristics cfg) {
    final remove = <int>{};
    final counts = <String, int>{};
    for (final segment in segments) {
      if (segment.uri.isEmpty) continue;
      counts[segment.uri] = (counts[segment.uri] ?? 0) + 1;
    }
    if (counts.length < 4) return remove;
    for (var i = 0; i < segments.length; i++) {
      final segment = segments[i];
      if (segment.uri.isEmpty) continue;
      final seen = counts[segment.uri] ?? 0;
      if (seen >= 3 &&
          segment.durationSec > 0.0 &&
          segment.durationSec <= cfg.maxAdBlockDurationSec) {
        remove.add(i);
      }
    }
    return remove;
  }

  /// Removes a short, uniformly-timed block of segments that is bounded on both
  /// sides by clearly longer content, with no discontinuity inside. Disabled by
  /// default: segment duration alone is an unreliable ad signal in HLS.
  static Set<int> _detectDurationWindows(
      List<Segment> segments, Heuristics cfg) {
    final remove = <int>{};
    var start = 0;
    while (start < segments.length) {
      if (!_isAdLikeDuration(segments[start], cfg)) {
        start++;
        continue;
      }
      var end = start;
      while (end < segments.length && _isAdLikeDuration(segments[end], cfg)) {
        end++;
      }
      final length = end - start;
      final boundedLeft = start > 0 && _isLongContent(segments[start - 1], cfg);
      final boundedRight =
          end < segments.length && _isLongContent(segments[end], cfg);
      var noDiscontinuity = true;
      for (var i = start; i < end; i++) {
        if (segments[i].discontinuityBefore > 0) {
          noDiscontinuity = false;
          break;
        }
      }
      if (length >= cfg.minAdBlockSegments &&
          length <= cfg.maxAdBlockSegments &&
          boundedLeft &&
          boundedRight &&
          noDiscontinuity) {
        for (var i = start; i < end; i++) {
          remove.add(i);
        }
      }
      start = end;
    }
    return remove;
  }

  static bool _isAdLikeDuration(Segment segment, Heuristics cfg) {
    final duration = segment.durationSec;
    return duration >= cfg.minAdBlockDurationSec &&
        duration <= cfg.maxAdBlockDurationSec;
  }

  static bool _isLongContent(Segment segment, Heuristics cfg) {
    return segment.durationSec > cfg.maxAdBlockDurationSec;
  }

  static String? _dominantHost(List<Segment> segments) {
    final hosts = <String, int>{};
    for (final segment in segments) {
      final host = _hostOf(segment.uri);
      if (host == null || host.isEmpty) continue;
      hosts[host] = (hosts[host] ?? 0) + 1;
    }
    String? best;
    var bestCount = 0;
    for (final entry in hosts.entries) {
      if (entry.value > bestCount) {
        best = entry.key;
        bestCount = entry.value;
      }
    }
    return best;
  }

  static String? _hostOf(String uri) {
    final scheme = uri.indexOf('://');
    final start = scheme >= 0 ? scheme + 3 : 0;
    var end = start;
    while (end < uri.length) {
      final c = uri.codeUnitAt(end);
      if (c == 0x2F || c == 0x3F || c == 0x23 || c == 0x3A) break;
      end++;
    }
    final host = uri.substring(start, end).trim();
    return host.isEmpty ? null : host.toLowerCase();
  }
}
