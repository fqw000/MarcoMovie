import 'dart:convert';

/// Converts third-party rule sources (TVBox `XC.json`, anti-AD hosts lists, and
/// m3u8-proxy-style regex rule sets) into the unified internal `AdFilterRules`
/// JSON schema.
class RulesImporter {
  RulesImporter._();

  /// Maps TVBox site configs to host-scoped regex rules.
  static String? fromXcJson(String? xcJson) {
    if (xcJson == null || xcJson.trim().isEmpty) return null;
    try {
      final parsed = jsonDecode(xcJson.trim());
      final sites = <dynamic>[];
      if (parsed is List) {
        sites.addAll(parsed);
      } else if (parsed is Map) {
        sites.add(parsed);
      } else {
        return null;
      }
      final hostRules = <Map<String, dynamic>>[];
      for (final siteValue in sites) {
        if (siteValue is! Map<String, dynamic>) continue;
        final site = siteValue;
        var host = _optString(site, 'host', '').trim();
        if (host.isEmpty) {
          host = _hostFromUrl(_optString(site, 'api', _optString(site, 'url', '')));
        }
        if (host.isEmpty) continue;
        final regex = <String>{};
        _collectStrings(site['filter'], regex);
        _collectStrings(site['rules'], regex);
        if (regex.isEmpty) continue;
        hostRules.add({
          'host': host,
          'regex': regex.toList(),
          'keywords': <String>[],
        });
      }
      if (hostRules.isEmpty) return null;
      final root = <String, dynamic>{
        'version': 2,
        'playlistRules': <dynamic>[],
        'hostRules': hostRules,
        'blockedDomains': <dynamic>[],
        'blockedUrlPatterns': <dynamic>[],
        'heuristics': _defaultHeuristics(),
      };
      return jsonEncode(root);
    } catch (_) {
      return null;
    }
  }

  /// Maps an anti-AD / hosts-style text list to blocked domains.
  static String? fromAntiAd(String? hostsText) {
    if (hostsText == null || hostsText.trim().isEmpty) return null;
    final domains = <String>{};
    for (final line in hostsText.split(RegExp(r'\r?\n'))) {
      var trimmed = line.trim();
      if (trimmed.isEmpty || trimmed.startsWith('#') || trimmed.startsWith('!')) {
        continue;
      }
      final comment = trimmed.indexOf('#');
      if (comment >= 0) trimmed = trimmed.substring(0, comment).trim();
      final domain = _domainFromHostsLine(trimmed);
      if (domain != null) domains.add(domain);
    }
    if (domains.isEmpty) return null;
    try {
      final root = <String, dynamic>{
        'version': 2,
        'playlistRules': <dynamic>[],
        'hostRules': <dynamic>[],
        'blockedDomains': domains.toList(),
        'blockedUrlPatterns': <dynamic>[],
        'heuristics': _defaultHeuristics(),
      };
      return jsonEncode(root);
    } catch (_) {
      return null;
    }
  }

  /// Maps an m3u8-proxy-style rule JSON (regex/keyword lists) to global
  /// playlist rules.
  static String? fromM3u8Proxy(String? rulesJson) {
    if (rulesJson == null || rulesJson.trim().isEmpty) return null;
    try {
      final parsed = jsonDecode(rulesJson.trim());
      final regex = <String>{};
      _collectAllStrings(parsed, regex);
      if (regex.isEmpty) return null;
      final playlist = <Map<String, dynamic>>[];
      for (final pattern in regex) {
        playlist.add({
          'type': 'regex',
          'pattern': pattern,
        });
      }
      final root = <String, dynamic>{
        'version': 2,
        'playlistRules': playlist,
        'hostRules': <dynamic>[],
        'blockedDomains': <dynamic>[],
        'blockedUrlPatterns': <dynamic>[],
        'heuristics': _defaultHeuristics(),
      };
      return jsonEncode(root);
    } catch (_) {
      return null;
    }
  }

  /// Merges one or more internal-schema JSON documents into a single schema.
  static String? merge(List<String?> internalJsons) {
    final playlist = <dynamic>[];
    final hostRules = <dynamic>[];
    final domainSet = <String>{};
    final urlSet = <String>{};
    for (final json in internalJsons) {
      if (json == null || json.trim().isEmpty) continue;
      try {
        final root = jsonDecode(json);
        if (root is! Map<String, dynamic>) continue;
        final p = root['playlistRules'];
        if (p is List) {
          for (final item in p) {
            playlist.add(item);
          }
        }
        final h = root['hostRules'];
        if (h is List) {
          for (final item in h) {
            hostRules.add(item);
          }
        }
        final d = root['blockedDomains'];
        if (d is List) {
          for (final item in d) {
            domainSet.add(_optStringValue(item));
          }
        }
        final u = root['blockedUrlPatterns'];
        if (u is List) {
          for (final item in u) {
            urlSet.add(_optStringValue(item));
          }
        }
      } catch (_) {
        // Ignore malformed entries.
      }
    }
    try {
      final root = <String, dynamic>{
        'version': 2,
        'playlistRules': playlist,
        'hostRules': hostRules,
        'blockedDomains': domainSet.toList(),
        'blockedUrlPatterns': urlSet.toList(),
        'heuristics': _defaultHeuristics(),
      };
      return jsonEncode(root);
    } catch (_) {
      return null;
    }
  }

  static Map<String, dynamic> _defaultHeuristics() {
    return {
      'enabled': true,
      'minAdBlockDurationSec': 4,
      'maxAdBlockDurationSec': 30,
      'minAdBlockSegments': 2,
      'maxAdBlockSegments': 8,
      'urlPrefixAnomaly': false,
      'keyNoneSwitch': true,
      'mirrorReuse': false,
      'durationWindows': false,
    };
  }

  /// Converts any accepted rule input (internal schema, TVBox XC.json, anti-AD
  /// hosts text, or m3u8-proxy rules) into the internal schema, or returns null.
  static String? importText(String? text) {
    if (text == null) return null;
    final trimmed = text.trim();
    if (trimmed.isEmpty) return null;
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      final internal = AdFilterRules.parse(trimmed);
      if (internal.hasAnyRule() || internal.heuristicsSpecified) return trimmed;
      return fromXcJson(trimmed) ?? fromM3u8Proxy(trimmed);
    }
    return fromAntiAd(trimmed);
  }

  static String? _domainFromHostsLine(String line) {
    final parts = line.split(RegExp(r'\s+'));
    for (var part in parts) {
      part = part.trim();
      if (part.isEmpty || part.startsWith('#') || part.startsWith('!')) continue;
      if (part.startsWith('0.0.0.0') ||
          part.startsWith('127.0.0.1') ||
          part.startsWith('::')) {
        continue;
      }
      var domain = part;
      if (domain.startsWith('*.')) domain = domain.substring(2);
      domain = domain.toLowerCase();
      if (domain.isEmpty) continue;
      return domain;
    }
    return null;
  }

  static String _hostFromUrl(String url) {
    if (url.trim().isEmpty) return '';
    var candidate = url.trim();
    final scheme = candidate.indexOf('://');
    if (scheme >= 0) candidate = candidate.substring(scheme + 3);
    final slash = candidate.indexOf('/');
    if (slash >= 0) candidate = candidate.substring(0, slash);
    final colon = candidate.indexOf(':');
    if (colon >= 0) candidate = candidate.substring(0, colon);
    return candidate.toLowerCase();
  }

  static void _collectStrings(dynamic value, Set<String> out) {
    if (value == null) return;
    if (value is String) {
      final text = value.trim();
      if (text.isEmpty) return;
      for (final line in text.split(RegExp(r'\r?\n'))) {
        final trimmed = line.trim();
        if (trimmed.isNotEmpty) out.add(trimmed);
      }
      return;
    }
    if (value is List) {
      for (final element in value) {
        if (element is String) {
          final trimmed = element.trim();
          if (trimmed.isNotEmpty) out.add(trimmed);
        }
      }
      return;
    }
    if (value is Map) {
      for (final key in value.keys) {
        final element = value[key];
        if (element is String) {
          final trimmed = element.trim();
          if (trimmed.isNotEmpty) out.add(trimmed);
        }
      }
    }
  }

  static void _collectAllStrings(dynamic value, Set<String> out) {
    if (value == null) return;
    if (value is String) {
      final trimmed = value.trim();
      if (trimmed.isNotEmpty) out.add(trimmed);
      return;
    }
    if (value is List) {
      for (final element in value) {
        _collectAllStrings(element, out);
      }
      return;
    }
    if (value is Map) {
      for (final key in value.keys) {
        _collectAllStrings(value[key], out);
      }
    }
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
