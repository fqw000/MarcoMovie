import 'package:flutter/services.dart' show rootBundle;

import 'ad_filter_rules.dart';
import 'storage_service.dart';

/// Loads the ad-filter rule set for the Flutter playback path.
///
/// Precedence matches the Android TV implementation: an explicit custom rules
/// JSON stored by the user, otherwise the bundled builtin rules, and neither
/// when the feature is disabled.
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
    final custom = storage.adFilterRulesJson.trim();
    if (custom.isNotEmpty) {
      final parsed = AdFilterRules.parse(custom);
      if (parsed.hasAnyRule()) return parsed;
    }
    final builtin = await _loadBuiltinJson();
    return AdFilterRules.parse(builtin ?? '');
  }
}
