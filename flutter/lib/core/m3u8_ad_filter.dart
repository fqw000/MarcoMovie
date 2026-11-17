import 'ad_filter_rules.dart';

/// A parsed media-playlist segment. Mirrors `M3u8AdFilter.Segment`.
class Segment {
  String uri = '';
  double durationSec = 0.0;
  String? keyMethod; // null = no KEY seen, "NONE", or e.g. "AES-128"
  int discontinuityBefore = 0; // #EXT-X-DISCONTINUITY markers since previous segment
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

    // Rule-based removal is only safe for clear streams: deleting a segment in
    // the middle of an AES-128 stream shifts the implicit IV of every following
    // segment and breaks decryption.
    if (!encrypted) {
      for (final segment in segments) {
        if (rules.matchesSegment(playlistHost, segment.uri)) {
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
      pendingDiscontinuity = 0;
      segments.add(segment);
      i = uriLine;
    }
    return segments;
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
      remove.addAll(_detectUrlPrefixAnomaly(segments, cfg));
    }
    if (cfg.mirrorReuse) {
      remove.addAll(_detectMirrorReuse(segments));
    }
    remove.addAll(_detectDurationWindows(segments, cfg));
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
      if (length >= cfg.minAdBlockSegments && (boundedLeft || boundedRight)) {
        for (var i = start; i < end; i++) {
          remove.add(i);
        }
      }
      start = end;
    }
    return remove;
  }

  /// Removes contiguous runs whose URL prefix diverges from the playlist's
  /// dominant prefix.
  static Set<int> _detectUrlPrefixAnomaly(
      List<Segment> segments, Heuristics cfg) {
    final remove = <int>{};
    final prefix = _dominantPrefix(segments);
    if (prefix == null || prefix.isEmpty) return remove;
    var start = 0;
    while (start < segments.length) {
      if (segments[start].uri.startsWith(prefix)) {
        start++;
        continue;
      }
      var end = start;
      while (end < segments.length && !segments[end].uri.startsWith(prefix)) {
        end++;
      }
      final length = end - start;
      final boundedLeft =
          start > 0 && segments[start - 1].uri.startsWith(prefix);
      final boundedRight =
          end < segments.length && segments[end].uri.startsWith(prefix);
      if (length >= cfg.minAdBlockSegments && (boundedLeft || boundedRight)) {
        for (var i = start; i < end; i++) {
          remove.add(i);
        }
      }
      start = end;
    }
    return remove;
  }

  /// Removes every occurrence of a URI that repeats across the playlist (ad
  /// loop evidence).
  static Set<int> _detectMirrorReuse(List<Segment> segments) {
    final remove = <int>{};
    final counts = <String, int>{};
    for (final segment in segments) {
      if (segment.uri.isEmpty) continue;
      counts[segment.uri] = (counts[segment.uri] ?? 0) + 1;
    }
    if (counts.length <= 1) return remove;
    for (var i = 0; i < segments.length; i++) {
      final uri = segments[i].uri;
      if (uri.isNotEmpty && (counts[uri] ?? 0) >= 2) remove.add(i);
    }
    return remove;
  }

  /// Removes isolated windows of short-duration segments sandwiched between
  /// longer content.
  static Set<int> _detectDurationWindows(
      List<Segment> segments, Heuristics cfg) {
    final remove = <int>{};
    var contentDuration = 0.0;
    for (final segment in segments) {
      if (segment.durationSec > contentDuration) {
        contentDuration = segment.durationSec;
      }
    }
    if (contentDuration <= 0.0) return remove;
    var start = 0;
    while (start < segments.length) {
      if (!_isAdLikeDuration(segments[start], cfg, contentDuration)) {
        start++;
        continue;
      }
      var end = start;
      while (end < segments.length &&
          _isAdLikeDuration(segments[end], cfg, contentDuration)) {
        end++;
      }
      final length = end - start;
      final boundedContentLeft = start > 0 &&
          !_isAdLikeDuration(segments[start - 1], cfg, contentDuration);
      final boundedContentRight = end < segments.length &&
          !_isAdLikeDuration(segments[end], cfg, contentDuration);
      if (length >= cfg.minAdBlockSegments &&
          (boundedContentLeft || boundedContentRight)) {
        for (var i = start; i < end; i++) {
          remove.add(i);
        }
      }
      start = end;
    }
    return remove;
  }

  static bool _isAdLikeDuration(
      Segment segment, Heuristics cfg, double contentDuration) {
    final duration = segment.durationSec;
    return duration > 0.0 &&
        duration >= cfg.minAdBlockDurationSec &&
        duration <= cfg.maxAdBlockDurationSec &&
        duration < contentDuration;
  }

  static String? _dominantPrefix(List<Segment> segments) {
    final prefixes = <String, int>{};
    for (final segment in segments) {
      final prefix = _urlPrefix(segment.uri);
      if (prefix == null || prefix.isEmpty) continue;
      prefixes[prefix] = (prefixes[prefix] ?? 0) + 1;
    }
    String? best;
    var bestCount = 0;
    for (final entry in prefixes.entries) {
      if (entry.value > bestCount) {
        best = entry.key;
        bestCount = entry.value;
      }
    }
    return best;
  }

  static String? _urlPrefix(String uri) {
    final slash = uri.lastIndexOf('/');
    if (slash < 0) return null;
    return uri.substring(0, slash + 1);
  }
}
