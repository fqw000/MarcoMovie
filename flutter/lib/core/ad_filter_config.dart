import 'package:flutter/services.dart' show rootBundle;

import 'ad_filter_rules.dart';
import 'storage_service.dart';

/// Loads the ad-filter rule set for the Flutter playback path.
///
/// Matches the Android TV implementation: the built-in rules are combined with
/// any stored custom rules, the built-in layer can be disabled independently,
/// and nothing is applied when the feature is off.
class AdFilterConfig {
  AdFilterConfig._();

  static const String builtinAsset = 'assets/adfilter/builtin_rules.json';
  static String? _builtinJson;

  static Future<String?> _loadBuiltinJson() async {
    final cached = _builtinJson;
    if (cached != null) return cached.isEmpty ? null : cached;
    try {
      _builtinJson = await rootBundle.loadString(builtinAsset);
    } catch (_) {
      _builtinJson = '';
    }
    return _builtinJson == null || (_builtinJson?.isEmpty ?? true)
        ? null
        : _builtinJson;
  }

  static Future<AdFilterRules> load(StorageService storage) async {
    if (!storage.adFilterEnabled) return AdFilterRules.empty();
    final builtin = storage.useBuiltinRules
        ? AdFilterRules.parse(await _loadBuiltinJson() ?? '')
        : AdFilterRules.empty();
    final customJson = storage.adFilterRulesJson.trim();
    final custom = customJson.isNotEmpty
        ? AdFilterRules.parse(customJson)
        : AdFilterRules.empty();
    return AdFilterRules.merge(builtin, custom);
  }
}
