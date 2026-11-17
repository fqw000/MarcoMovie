import 'dart:convert';

/// Host-scoped playlist rule. Mirrors the Java nested `AdFilterRules.HostRule`.
class HostRule {
  final String host;
  final List<RegExp> regex;
  final List<String> keywords;

  HostRule(String host, this.regex, this.keywords)
      : host = host.trim().toLowerCase();

  bool matches(String uri) {
    for (final keyword in keywords) {
      if (keyword.isNotEmpty && uri.contains(keyword)) return true;
    }
    for (final pattern in regex) {
      if (pattern.hasMatch(uri)) return true;
    }
    return false;
  }
}

/// Heuristic configuration. Mirrors the Java nested `AdFilterRules.Heuristics`.
class Heuristics {
  bool enabled = false;
  int minAdBlockDurationSec = 3;
  int maxAdBlockDurationSec = 75;
  int minAdBlockSegments = 2;
  bool urlPrefixAnomaly = true;
  bool keyNoneSwitch = true;
  bool mirrorReuse = true;
}

/// Ad-filter rule model for HLS playlist and request filtering.
class AdFilterRules {
  final List<RegExp> globalRegex;
  final List<String> globalKeywords;
  final List<HostRule> hostRules;
  final Set<String> blockedDomains;
  final List<RegExp> blockedUrlPatterns;
  final Heuristics heuristics;

  AdFilterRules._(
      this.globalRegex,
      this.globalKeywords,
      this.hostRules,
      this.blockedDomains,
      this.blockedUrlPatterns,
      this.heuristics);

  static AdFilterRules empty() {
    return AdFilterRules._(
        const <RegExp>[],
        const <String>[],
        const <HostRule>[],
        <String>{},
        const <RegExp>[],
        Heuristics());
  }

  /// Parses the internal schema. Returns an empty rule set on malformed JSON.
  static AdFilterRules parse(String? json) {
    if (json == null || json.trim().isEmpty) return empty();
    try {
      final root = jsonDecode(json);
      if (root is! Map<String, dynamic>) return empty();

      final globalRegex = <RegExp>[];
      final globalKeywords = <String>[];
      final playlist = root['playlistRules'];
      if (playlist is List) {
        for (final item in playlist) {
          if (item is! Map<String, dynamic>) continue;
          final type = _optString(item, 'type', '');
          final pattern = _optString(item, 'pattern', '');
          if (pattern.isEmpty) continue;
          if (type == 'keyword') {
            globalKeywords.add(pattern);
          } else {
            final compiled = _compile(pattern);
            if (compiled != null) globalRegex.add(compiled);
          }
        }
      }

      final hostRules = <HostRule>[];
      final hosts = root['hostRules'];
      if (hosts is List) {
        for (final item in hosts) {
          if (item is! Map<String, dynamic>) continue;
          final host = _optString(item, 'host', '');
          if (host.isEmpty) continue;
          final regex = <RegExp>[];
          final regexArr = item['regex'];
          if (regexArr is List) {
            for (final entry in regexArr) {
              final compiled = _compile(_optStringValue(entry));
              if (compiled != null) regex.add(compiled);
            }
          }
          final keywords = <String>[];
          final keywordArr = item['keywords'];
          if (keywordArr is List) {
            for (final entry in keywordArr) {
              final keyword = _optStringValue(entry);
              if (keyword.isNotEmpty) keywords.add(keyword);
            }
          }
          hostRules.add(HostRule(host, regex, keywords));
        }
      }

      final domains = <String>{};
      final domainArr = root['blockedDomains'];
      if (domainArr is List) {
        for (final entry in domainArr) {
          var domain = _optStringValue(entry).trim().toLowerCase();
          if (domain.startsWith('*.')) domain = domain.substring(2);
          if (domain.isNotEmpty) domains.add(domain);
        }
      }

      final urlPatterns = <RegExp>[];
      final urlArr = root['blockedUrlPatterns'];
      if (urlArr is List) {
        for (final entry in urlArr) {
          final compiled = _compile(_optStringValue(entry));
          if (compiled != null) urlPatterns.add(compiled);
        }
      }

      final heuristics = Heuristics();
      final heu = root['heuristics'];
      if (heu is Map<String, dynamic>) {
        heuristics.enabled = _optBool(heu, 'enabled', false);
        heuristics.minAdBlockDurationSec =
            _optInt(heu, 'minAdBlockDurationSec', 3);
        heuristics.maxAdBlockDurationSec =
            _optInt(heu, 'maxAdBlockDurationSec', 75);
        heuristics.minAdBlockSegments = _optInt(heu, 'minAdBlockSegments', 2);
        heuristics.urlPrefixAnomaly = _optBool(heu, 'urlPrefixAnomaly', true);
        heuristics.keyNoneSwitch = _optBool(heu, 'keyNoneSwitch', true);
        heuristics.mirrorReuse = _optBool(heu, 'mirrorReuse', true);
      }

      return AdFilterRules._(globalRegex, globalKeywords, hostRules, domains,
          urlPatterns, heuristics);
    } catch (_) {
      return empty();
    }
  }

  static RegExp? _compile(String pattern) {
    if (pattern.trim().isEmpty) return null;
    try {
      return RegExp(pattern);
    } catch (_) {
      return null;
    }
  }

  bool hasAnyRule() {
    return globalRegex.isNotEmpty ||
        globalKeywords.isNotEmpty ||
        hostRules.isNotEmpty ||
        blockedDomains.isNotEmpty ||
        blockedUrlPatterns.isNotEmpty;
  }

  bool isBlockedHost(String? host) {
    if (host == null || blockedDomains.isEmpty) return false;
    final normalized = host.trim().toLowerCase();
    if (blockedDomains.contains(normalized)) return true;
    for (final domain in blockedDomains) {
      if (normalized.endsWith('.' + domain)) return true;
    }
    return false;
  }

  bool isBlockedUrl(String? url) {
    if (url == null || url.isEmpty) return false;
    for (final pattern in blockedUrlPatterns) {
      if (pattern.hasMatch(url)) return true;
    }
    return false;
  }

  bool matchesSegment(String? playlistHost, String? uri) {
    if (uri == null || uri.isEmpty) return false;
    for (final keyword in globalKeywords) {
      if (uri.contains(keyword)) return true;
    }
    for (final pattern in globalRegex) {
      if (pattern.hasMatch(uri)) return true;
    }
    if (hostRules.isNotEmpty && playlistHost != null) {
      final normalized = playlistHost.trim().toLowerCase();
      for (final rule in hostRules) {
        if (normalized == rule.host || normalized.endsWith('.' + rule.host)) {
          if (rule.matches(uri)) return true;
        }
      }
    }
    return false;
  }
}

String _optString(Map<String, dynamic> map, String key, String fallback) {
  final value = map[key];
  if (value == null) return fallback;
  if (value is String) return value;
  return value.toString();
}

String _optStringValue(dynamic value) {
  if (value == null) return '';
  if (value is String) return value;
  return value.toString();
}

int _optInt(Map<String, dynamic> map, String key, int fallback) {
  final value = map[key];
  if (value == null) return fallback;
  if (value is int) return value;
  if (value is double) return value.toInt();
  if (value is String) return int.tryParse(value.trim()) ?? fallback;
  return fallback;
}

bool _optBool(Map<String, dynamic> map, String key, bool fallback) {
  final value = map[key];
  if (value == null) return fallback;
  if (value is bool) return value;
  if (value is String) return value.toLowerCase() == 'true';
  return fallback;
}
