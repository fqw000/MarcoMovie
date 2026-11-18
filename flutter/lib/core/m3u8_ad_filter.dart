import 'dart:math' as math;

import 'ad_filter_rules.dart';

/// A parsed media-playlist segment node. Mirrors `M3u8AdFilter.Node`.
class Segment {
  int index = 0;
  String extinf = '';
  String uri = '';
  double durationSec = 0.0;
  String? keyMethod; // null = no KEY seen, "NONE", or e.g. "AES-128"
  String? keyRaw; // raw #EXT-X-KEY line, for structural conservation
  bool hasDisc = false; // #EXT-X-DISCONTINUITY seen before this node
  bool scteAd = false; // inside a CUE-OUT/IN or SCTE-35 marked ad break
  bool adComment = false; // private ad-injection comment seen before this node
  List<String> tags = <String>[]; // other tag lines (CUE, DATERANGE, comments)
  bool ruleHit = false; // segment URI matched by rules
  bool keynone = false; // clear segment inside an otherwise encrypted stream
  bool namingReuse = false; // naming-key reuse evidence
  bool mirrorReuse = false; // exact URI reuse evidence
  String? reason; // human-readable evidence, for diagnostics
  bool dropped = false;
}

/// Filter result. Mirrors `M3u8AdFilter.Result`.
class M3u8FilterResult {
  final String text;
  final int removedSegments;
  final String reason;

  M3u8FilterResult(this.text, this.removedSegments, [this.reason = '']);
}

/// Pure HLS media-playlist filter: removes ad segments matched by
/// [AdFilterRules] and by [HeuristicAdDetector], then rebuilds a structurally
/// valid playlist.
class M3u8AdFilter {
  M3u8AdFilter._();

  /// Private comment markers left by ad-stitching systems, e.g.
  /// `# ppvod-ad-injected v1`.
  static final RegExp _adComment = RegExp(
    r'(ad[-_]?(inject|insert|mark|marker)|ppvod|guanggao|adseg|插播|广告)',
    caseSensitive: false,
  );

  static M3u8FilterResult filter(
      String? m3u8, String? playlistHost, AdFilterRules rules) {
    if (m3u8 == null || m3u8.trim().isEmpty) {
      return M3u8FilterResult(m3u8 ?? '', 0, 'empty');
    }
    final lines = _splitLines(m3u8);
    if (lines.isEmpty || !lines[0].trim().startsWith('#EXTM3U')) {
      return M3u8FilterResult(m3u8, 0, 'not-m3u8');
    }

    final parsed = _parse(lines);
    if (parsed.head.isEmpty && parsed.nodes.isEmpty) {
      return M3u8FilterResult(m3u8, 0, 'no-content');
    }

    // Master playlists are never edited; the variant media playlist is filtered
    // instead.
    if (_isMaster(parsed.head)) return M3u8FilterResult(m3u8, 0, 'master');

    // Live / EVENT playlists have a sliding window; deleting segments breaks
    // sequence math.
    if (!_hasEndList(parsed.tail) && !_containsLine(m3u8, '#EXT-X-ENDLIST')) {
      return M3u8FilterResult(m3u8, 0, 'live-no-endlist');
    }

    // AES-128 / SAMPLE-AES without an explicit IV derives each IV from the
    // segment sequence number, so removing any segment shifts every following
    // IV and breaks decryption.
    if (parsed.implicitIv) {
      return M3u8FilterResult(m3u8, 0, 'aes-implicit-iv');
    }

    if (parsed.nodes.isEmpty) return M3u8FilterResult(m3u8, 0, 'no-segments');

    final heu = rules.heuristics;

    // Reliable per-segment evidence: URL rules and SCTE/CUE markers. These are
    // unambiguous and always run, independent of the heuristic switch.
    for (final node in parsed.nodes) {
      if (rules.matchesSegment(playlistHost, node.uri)) {
        node.ruleHit = true;
        node.dropped = true;
        node.reason = 'rule';
      }
      if (node.scteAd) {
        node.dropped = true;
        node.reason = _first(node.reason, 'scte');
      }
    }

    // Private comment markers (AD-02) mark the whole discontinuity-bounded
    // block, e.g. "# ppvod-ad-injected v1" immediately before a
    // DISCONTINUITY-bounded ad pod.
    for (var i = 0; i < parsed.nodes.length; i++) {
      final marker = parsed.nodes[i];
      if (!marker.adComment) continue;
      var j = i;
      while (j < parsed.nodes.length) {
        if (j > i && parsed.nodes[j].hasDisc) break; // closing boundary
        final block = parsed.nodes[j];
        block.dropped = true;
        if (block.reason == null) block.reason = 'ad-comment';
        j++;
      }
    }

    // Heuristic chunk detection (KEY-NONE clear blocks + discontinuity-bounded
    // shape scoring).
    if (heu.enabled) {
      final dropped = HeuristicAdDetector.detect(
          parsed.nodes, heu, playlistHost, parsed.encrypted);
      for (final i in dropped) {
        final n = parsed.nodes[i];
        n.dropped = true;
        if (n.reason == null) n.reason = 'heuristic';
      }
    }

    var removed = 0;
    for (final n in parsed.nodes) {
      if (n.dropped) removed++;
    }
    if (removed == 0) return M3u8FilterResult(m3u8, 0, 'clean');

    // Safety cap: a single small block is always allowed, but a large deletion
    // is suspicious.
    final maxBlock = heu.maxAdBlockSegments;
    var removedDur = 0.0;
    for (final n in parsed.nodes) {
      if (n.dropped) removedDur += n.durationSec;
    }
    final ratio =
        parsed.nodes.isEmpty ? 0.0 : removed / parsed.nodes.length;
    if (removed > maxBlock && (ratio > 0.15 || removedDur > 120.0)) {
      return M3u8FilterResult(m3u8, 0, 'revert-over-cap');
    }

    final rebuilt = _rebuild(parsed);
    final verified =
        _verifyAndFallback(m3u8, rebuilt, parsed.nodes.length - removed);
    if (verified == null) {
      return M3u8FilterResult(m3u8, 0, 'revert-invariant');
    }
    return M3u8FilterResult(verified, removed, 'changed');
  }

  static String _first(String? a, String b) {
    return a == null ? b : '$a+$b';
  }

  /* ------------------------------------------------------------------ parsing */

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

  static bool _isMaster(List<String> head) {
    for (final line in head) {
      if (line.trim().startsWith('#EXT-X-STREAM-INF')) return true;
    }
    return false;
  }

  static bool _hasEndList(List<String> tail) {
    for (final line in tail) {
      if (line.trim().startsWith('#EXT-X-ENDLIST')) return true;
    }
    return false;
  }

  static bool _containsLine(String text, String needle) {
    return text.indexOf(needle) >= 0;
  }

  static _Parsed _parse(List<String> lines) {
    final head = <String>[];
    final nodes = <Segment>[];
    final tail = <String>[];

    final pending = <String>[];
    String? currentKeyRaw;
    String? currentKeyMethod;
    var implicitIv = false;
    var encrypted = false;
    var inScte = false;
    var pendingAdComment = false;
    String? pendingExtinf;
    var seenSegment = false;

    for (final line in lines) {
      final t = line.trim();
      if (t.isEmpty) continue;

      if (t.startsWith('#EXTINF:')) {
        pendingExtinf = t;
        continue;
      }

      if (!t.startsWith('#')) {
        if (pendingExtinf != null) {
          final node = Segment();
          node.index = nodes.length;
          node.extinf = pendingExtinf;
          node.uri = t;
          node.durationSec = _parseDuration(pendingExtinf);
          node.keyRaw = currentKeyRaw;
          node.keyMethod = currentKeyMethod;
          node.scteAd = inScte;
          node.adComment = pendingAdComment;
          node.tags = List<String>.of(pending);
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

      if (!seenSegment && pendingExtinf == null && _isGlobalHead(t)) {
        head.add(t);
        continue;
      }
      if (t.startsWith('#EXT-X-KEY:')) {
        currentKeyRaw = t;
        currentKeyMethod = _parseKeyMethod(t);
        if (_isAes(currentKeyMethod) && !_hasIv(t)) implicitIv = true;
        if (currentKeyMethod != null && currentKeyMethod != 'NONE') {
          encrypted = true;
        }
        continue;
      }
      if (t.startsWith('#EXT-X-DISCONTINUITY')) {
        pending.add(t);
        continue;
      }
      if (_isScteStart(t)) {
        inScte = true;
        pending.add(t);
        continue;
      }
      if (t.startsWith('#EXT-X-CUE-IN')) {
        inScte = false;
        pending.add(t);
        continue;
      }
      // Private comment markers (AD-02): "# ppvod-ad-injected v1" etc.
      if (t.codeUnitAt(0) == 0x23 &&
          !t.startsWith('#EXT') &&
          _adComment.hasMatch(t)) {
        pendingAdComment = true;
        pending.add(t);
        continue;
      }
      pending.add(t);
    }
    tail.addAll(pending);

    for (final node in nodes) {
      node.hasDisc = _hasDiscontinuity(node.tags);
    }
    return _Parsed(head, nodes, tail, implicitIv, encrypted);
  }

  static bool _hasDiscontinuity(List<String> tags) {
    for (final tag in tags) {
      if (tag.startsWith('#EXT-X-DISCONTINUITY')) return true;
    }
    return false;
  }

  static bool _isGlobalHead(String t) {
    return t.startsWith('#EXTM3U') ||
        t.startsWith('#EXT-X-VERSION') ||
        t.startsWith('#EXT-X-TARGETDURATION') ||
        t.startsWith('#EXT-X-MEDIA-SEQUENCE') ||
        t.startsWith('#EXT-X-DISCONTINUITY-SEQUENCE') ||
        t.startsWith('#EXT-X-PLAYLIST-TYPE') ||
        t.startsWith('#EXT-X-I-FRAMES-ONLY') ||
        t.startsWith('#EXT-X-INDEPENDENT-SEGMENTS') ||
        t.startsWith('#EXT-X-START') ||
        t.startsWith('#EXT-X-MAP') ||
        t.startsWith('#EXT-X-ALLOW-CACHE');
  }

  static bool _isAes(String? method) {
    return method != null &&
        (method.startsWith('AES-128') || method.startsWith('SAMPLE-AES'));
  }

  static bool _hasIv(String keyLine) {
    return keyLine.contains(',IV=');
  }

  static bool _isScteStart(String line) {
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

  /* ------------------------------------------------------------------ rebuild */

  static String _rebuild(_Parsed parsed) {
    final nodes = parsed.nodes;
    final head = <String>[...parsed.head];

    var droppedAtStart = 0;
    var discAtStart = 0;
    for (final n in nodes) {
      if (!n.dropped) break;
      droppedAtStart++;
      if (n.hasDisc) discAtStart++;
    }

    for (var i = 0; i < head.length; i++) {
      final line = head[i].trim();
      if (line.startsWith('#EXT-X-MEDIA-SEQUENCE:') && droppedAtStart > 0) {
        final numberText =
            line.substring('#EXT-X-MEDIA-SEQUENCE:'.length).trim();
        final v = int.tryParse(numberText);
        if (v != null) head[i] = '#EXT-X-MEDIA-SEQUENCE:${v + droppedAtStart}';
      } else if (line.startsWith('#EXT-X-DISCONTINUITY-SEQUENCE:') &&
          discAtStart > 0) {
        final numberText =
            line.substring('#EXT-X-DISCONTINUITY-SEQUENCE:'.length).trim();
        final v = int.tryParse(numberText);
        if (v != null) {
          head[i] = '#EXT-X-DISCONTINUITY-SEQUENCE:${v + discAtStart}';
        }
      }
    }

    final out = <String>[...head];
    String? emittedKey;
    var prevKept = -1;
    var discEmitted = false;

    for (var i = 0; i < nodes.length; i++) {
      final n = nodes[i];
      if (n.dropped) continue;

      if (n.keyRaw != null && n.keyRaw != emittedKey) {
        out.add(n.keyRaw!);
        emittedKey = n.keyRaw;
      }

      final gapDropped = _hasDroppedIn(nodes, prevKept + 1, i - 1);
      final wantDisc = n.hasDisc ||
          (gapDropped && _rangeHasDisc(nodes, prevKept + 1, i - 1));
      if (wantDisc && !discEmitted) out.add('#EXT-X-DISCONTINUITY');
      discEmitted = wantDisc;

      for (final tag in n.tags) {
        if (tag.startsWith('#EXT-X-KEY:')) continue;
        if (tag.startsWith('#EXT-X-DISCONTINUITY')) continue;
        out.add(tag);
      }
      out.add(n.extinf);
      out.add(n.uri);
      prevKept = i;
    }
    out.addAll(parsed.tail);

    return _balanceCues(out);
  }

  static bool _hasDroppedIn(List<Segment> nodes, int from, int to) {
    for (var i = from; i <= to; i++) {
      if (i >= 0 && i < nodes.length && nodes[i].dropped) return true;
    }
    return false;
  }

  static bool _rangeHasDisc(List<Segment> nodes, int from, int to) {
    for (var i = from; i <= to; i++) {
      if (i >= 0 && i < nodes.length && nodes[i].hasDisc) return true;
    }
    return false;
  }

  /// Drops orphaned CUE-IN / CUE-OUT left behind after the ad block is removed.
  static String _balanceCues(List<String> lines) {
    final out = StringBuffer();
    var open = false;
    for (final line in lines) {
      final t = line.trim();
      final cueStart = _isCueStart(t);
      final cueIn = t.startsWith('#EXT-X-CUE-IN');
      if (cueStart) {
        if (open) {
          final text = out.toString();
          final idx = text.lastIndexOf('#EXT-X-CUE-OUT');
          if (idx >= 0) {
            out.clear();
            out.write(text.substring(0, idx));
          }
        }
        open = true;
      } else if (cueIn) {
        if (!open) continue;
        open = false;
      }
      out.write(line);
      out.write('\n');
    }
    return out.toString();
  }

  static bool _isCueStart(String t) {
    return t.startsWith('#EXT-X-CUE-OUT') ||
        t.startsWith('#EXT-X-SCTE35') ||
        t.startsWith('#EXT-OATCLS-SCTE35');
  }

  /* ------------------------------------------------------------------ verify */

  static String? _verifyAndFallback(
      String original, String rebuilt, int expectedSegments) {
    final parsed = _parse(_splitLines(rebuilt));
    final segments = parsed.nodes.length;
    if (segments != expectedSegments) return null;
    if (segments < 1) return null;
    if (!rebuilt.trim().startsWith('#EXTM3U')) return null;
    // No two consecutive DISCONTINUITY markers.
    if (rebuilt.contains('#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY')) {
      return null;
    }
    return rebuilt;
  }
}

class _Parsed {
  final List<String> head;
  final List<Segment> nodes;
  final List<String> tail;
  final bool implicitIv;
  final bool encrypted;

  _Parsed(this.head, this.nodes, this.tail, this.implicitIv, this.encrypted);
}

/// Heuristic ad-segment detection for media playlists whose ad inserts cannot
/// be described by static regex/keyword rules.
class HeuristicAdDetector {
  HeuristicAdDetector._();

  static const double W_UNIFORM_SHORT = 0.40;
  static const double W_SHORT_ONLY = 0.20;
  static const double W_DISC = 0.15;
  static const double W_OFF_HOST = 0.15;
  static const double W_URL_DEV = 0.10;
  static const double W_SHORT_BETWEEN_LONG = 0.20;
  static const double W_NAMING_REUSE = 0.25;
  static const double W_DENSE_SHORT = 0.10;
  static const double W_SEQ_ISLAND = 0.08;
  static const double W_SINGLE = -0.30;
  static const double W_LONG = -0.20;
  static const double W_TOTAL = -0.30;

  static const double BASE_THRESHOLD = 0.60;
  static const double FIRST_BONUS = 0.20;
  static const double LAST_BONUS = 0.10;
  static const double UNIFORM_TOLERANCE = 0.8;

  /// Structural (non-duration) signals required to corroborate a weak
  /// shape-only hit.
  static const List<String> STRUCTURAL_NOTES = <String>[
    'disc',
    'offhost',
    'url-dev',
    'naming-reuse',
    'mirror-reuse',
    'seq-island',
  ];

  static Set<int> detect(List<Segment> nodes, Heuristics cfg,
      String? playlistHost, bool encrypted) {
    final drop = <int>{};
    if (nodes.isEmpty) return drop;

    final minSegments = cfg.minAdBlockSegments;
    final maxSegments = cfg.maxAdBlockSegments;
    final minDur = cfg.minAdBlockDurationSec;
    final maxTotal = cfg.maxAdBlockTotalSec;
    final requireStructural = cfg.requireStructural;
    final keyNone = cfg.keyNoneSwitch;

    // KEY-NONE: a clear block inside an otherwise encrypted stream is strong
    // evidence.
    if (encrypted && keyNone) {
      _detectKeyNone(nodes, minSegments, maxSegments, drop);
    }

    // Naming / mirror reuse evidence (clear streams only, to avoid key-URI
    // reuse confusion).
    if (!encrypted) {
      _detectReuse(nodes, cfg.maxAdBlockDurationSec);
    }

    // Discontinuity-bounded chunk scoring.
    final chunks = _buildChunks(nodes);
    final ctx = _buildContext(nodes, chunks);
    for (var i = 0; i < chunks.length; i++) {
      final chunk = chunks[i];
      final hit = _scoreChunk(chunk, i, chunks, ctx, minSegments, maxSegments,
          minDur, maxTotal, requireStructural);
      if (hit) {
        for (final n in chunk) {
          drop.add(n.index);
        }
      }
    }
    return drop;
  }

  /* ---------------------------------------------------------------- context */

  static _Context _buildContext(
      List<Segment> nodes, List<List<Segment>> chunks) {
    final ctx = _Context();
    final hosts = <String>[];
    for (final n in nodes) {
      final h = _hostOf(n.uri);
      if (h != null) hosts.add(h);
    }
    ctx.dominantHost = hosts.length >= nodes.length * 0.5 ? _mode(hosts) : null;

    final keys = <String>[];
    for (final n in nodes) {
      final k = _namingKey(n.uri);
      if (k.isNotEmpty) keys.add(k);
    }
    ctx.dominantKey = _mode(keys);

    final durs = <double>[];
    for (final n in nodes) {
      if (n.durationSec > 0) durs.add(n.durationSec);
    }
    ctx.medianDur = _median(durs);
    ctx.avgChunkSize = chunks.isEmpty ? nodes.length.toDouble() : nodes.length / chunks.length;
    return ctx;
  }

  /* ---------------------------------------------------------------- chunks */

  static List<List<Segment>> _buildChunks(List<Segment> nodes) {
    final chunks = <List<Segment>>[];
    var cur = <Segment>[];
    Segment? prev;
    for (final n in nodes) {
      var boundary = false;
      if (cur.isNotEmpty && prev != null) {
        if (n.hasDisc) {
          boundary = true;
        } else if (n.keyRaw != prev.keyRaw) {
          boundary = true;
        } else if (n.scteAd != prev.scteAd) {
          boundary = true;
        } else {
          final jump = (n.durationSec - prev.durationSec).abs();
          final scale = math
              .max(3.0, 0.35 * math.max(n.durationSec, prev.durationSec))
              .toDouble();
          if (jump >= scale) boundary = true;
        }
      }
      if (boundary) {
        chunks.add(cur);
        cur = <Segment>[];
      }
      cur.add(n);
      prev = n;
    }
    if (cur.isNotEmpty) chunks.add(cur);
    return chunks;
  }

  static bool _scoreChunk(
      List<Segment> chunk,
      int idx,
      List<List<Segment>> chunks,
      _Context ctx,
      int minSegments,
      int maxSegments,
      int minDur,
      int maxTotal,
      bool requireStructural) {
    final n = chunk.length;
    var total = 0.0;
    var min = double.infinity;
    var max = 0.0;
    for (final node in chunk) {
      total += node.durationSec;
      if (node.durationSec < min) min = node.durationSec;
      if (node.durationSec > max) max = node.durationSec;
    }
    final uniform = n >= 2 && (max - min) <= UNIFORM_TOLERANCE;
    final shortShape = min >= minDur && total <= maxTotal;

    var namingReuseCount = 0;
    var mirrorReuseCount = 0;
    var offHost = false;
    var urlDev = false;
    for (final node in chunk) {
      if (node.namingReuse) namingReuseCount++;
      if (node.mirrorReuse) mirrorReuseCount++;
      if (ctx.dominantHost != null) {
        final h = _hostOf(node.uri);
        if (h != null && h != ctx.dominantHost) offHost = true;
      }
      if (ctx.dominantKey != null && ctx.dominantKey!.isNotEmpty) {
        if (_namingKey(node.uri) != ctx.dominantKey) urlDev = true;
      }
    }

    final notes = <String>[];
    var score = 0.0;
    if (shortShape && uniform) {
      score += W_UNIFORM_SHORT;
      notes.add('uniform-short');
    } else if (shortShape) {
      score += W_SHORT_ONLY;
      notes.add('short');
    }
    if (n < minSegments) {
      score += W_SINGLE;
      notes.add('single');
    }
    if (n > maxSegments) {
      score += W_LONG;
      notes.add('long$n');
    }
    if (total > 90) {
      score += W_TOTAL;
      notes.add('total>90s');
    }
    if (chunk.isNotEmpty && chunk.first.hasDisc) {
      score += W_DISC;
      notes.add('disc');
    }
    if (offHost) {
      score += W_OFF_HOST;
      notes.add('offhost');
    }
    if (urlDev) {
      score += W_URL_DEV;
      notes.add('url-dev');
    }
    if (namingReuseCount * 2 >= n) {
      score += W_NAMING_REUSE;
      notes.add('naming-reuse');
    }
    if (mirrorReuseCount * 2 >= n) {
      score += W_NAMING_REUSE;
      notes.add('mirror-reuse');
    }
    if (idx > 0 && idx < chunks.length - 1) {
      final prevChunk = chunks[idx - 1];
      final nextChunk = chunks[idx + 1];
      if (prevChunk.isNotEmpty && nextChunk.isNotEmpty) {
        final prevDur = prevChunk.last.durationSec;
        final nextDur = nextChunk.first.durationSec;
        if (prevDur >= 1.5 * max && nextDur >= 1.5 * max) {
          score += W_SHORT_BETWEEN_LONG;
          notes.add('short-between-long');
        }
        final curKey = _modeKey(chunk);
        final pk = _modeKey(prevChunk);
        final nk = _modeKey(nextChunk);
        if (curKey.isNotEmpty &&
            pk.isNotEmpty &&
            nk.isNotEmpty &&
            curKey != pk &&
            curKey != nk) {
          score += W_SEQ_ISLAND;
          notes.add('seq-island');
        }
      }
    }
    if (ctx.medianDur > 0 &&
        ctx.medianDur <= 5 &&
        uniform &&
        n >= 2 * math.max(1, ctx.avgChunkSize.round())) {
      score += W_DENSE_SHORT;
      notes.add('dense-short');
    }

    final threshold = BASE_THRESHOLD +
        (idx == 0 ? FIRST_BONUS : 0.0) +
        (idx == chunks.length - 1 ? LAST_BONUS : 0.0);
    var hit = score >= threshold;
    if (hit && requireStructural && !_hasStructural(notes)) {
      hit = false;
    }
    return hit;
  }

  static bool _hasStructural(List<String> notes) {
    for (final note in notes) {
      if (STRUCTURAL_NOTES.contains(note)) return true;
    }
    return false;
  }

  static String _modeKey(List<Segment> chunk) {
    final keys = <String>[];
    for (final node in chunk) {
      final k = _namingKey(node.uri);
      if (k.isNotEmpty) keys.add(k);
    }
    return _mode(keys);
  }

  /* ---------------------------------------------------------------- keynone */

  static void _detectKeyNone(
      List<Segment> nodes, int minSegments, int maxSegments, Set<int> drop) {
    var start = 0;
    while (start < nodes.length) {
      if (!_isClear(nodes[start])) {
        start++;
        continue;
      }
      var end = start;
      while (end < nodes.length && _isClear(nodes[end])) {
        end++;
      }
      final length = end - start;
      final left = start > 0 && !_isClear(nodes[start - 1]);
      final right = end < nodes.length && !_isClear(nodes[end]);
      if (length >= minSegments && length <= maxSegments && (left || right)) {
        for (var i = start; i < end; i++) {
          drop.add(nodes[i].index);
        }
      }
      start = end;
    }
  }

  static bool _isClear(Segment n) {
    return n.keyMethod == null || n.keyMethod == 'NONE';
  }

  /* ---------------------------------------------------------------- reuse */

  static void _detectReuse(List<Segment> nodes, int maxDur) {
    final uriCount = <String, int>{};
    final keyCount = <String, int>{};
    for (final n in nodes) {
      uriCount[n.uri] = (uriCount[n.uri] ?? 0) + 1;
      final k = _namingKey(n.uri);
      if (k.isNotEmpty) keyCount[k] = (keyCount[k] ?? 0) + 1;
    }
    for (final n in nodes) {
      if (uriCount.length >= 4 &&
          (uriCount[n.uri] ?? 0) >= 3 &&
          n.durationSec <= maxDur) {
        n.mirrorReuse = true;
      }
      final k = _namingKey(n.uri);
      if (k.isNotEmpty &&
          keyCount.length >= 3 &&
          (keyCount[k] ?? 0) >= 3 &&
          n.durationSec <= maxDur) {
        n.namingReuse = true;
      }
    }
  }

  /* ---------------------------------------------------------------- helpers */

  static String _namingKey(String uri) {
    var p = uri;
    final scheme = p.indexOf('://');
    if (scheme >= 0) {
      final hostStart = scheme + 3;
      final slash = p.indexOf('/', hostStart);
      if (slash >= 0) p = p.substring(slash);
    }
    var cut = p.length;
    final q = p.indexOf('?');
    final h = p.indexOf('#');
    if (q >= 0 && q < cut) cut = q;
    if (h >= 0 && h < cut) cut = h;
    p = p.substring(0, cut);
    return p.replaceAll(RegExp(r'\d+'), '#').toLowerCase();
  }

  static String? _hostOf(String uri) {
    final scheme = uri.indexOf('://');
    if (scheme < 0) return null; // relative URI carries no host
    final start = scheme + 3;
    var end = start;
    while (end < uri.length) {
      final c = uri.codeUnitAt(end);
      if (c == 0x2F || c == 0x3F || c == 0x23 || c == 0x3A) break;
      end++;
    }
    final host = uri.substring(start, end).trim();
    return host.isEmpty ? null : host.toLowerCase();
  }

  static String _mode(List<String> values) {
    final counts = <String, int>{};
    for (final v in values) {
      if (v.isEmpty) continue;
      counts[v] = (counts[v] ?? 0) + 1;
    }
    var best = '';
    var bestCount = 0;
    counts.forEach((key, value) {
      if (value > bestCount) {
        best = key;
        bestCount = value;
      }
    });
    return best;
  }

  static double _median(List<double> values) {
    if (values.isEmpty) return 0;
    final a = <double>[...values]..sort();
    final m = a.length >> 1;
    return a.length.isOdd ? a[m] : (a[m - 1] + a[m]) / 2.0;
  }
}

class _Context {
  String? dominantHost;
  String dominantKey = '';
  double medianDur = 0;
  double avgChunkSize = 0;
}
