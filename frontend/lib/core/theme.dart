import 'package:flutter/material.dart';

class AppTheme {
  /// What every button of the app is at least, filled or outlined alike.
  static const _buttonMinimum = Size(64, 46);

  static ThemeData light() {
    final scheme = ColorScheme.fromSeed(seedColor: const Color(0xFF1E88E5));
    return ThemeData(
      colorScheme: scheme,
      useMaterial3: true,
      visualDensity: VisualDensity.adaptivePlatformDensity,
      appBarTheme: const AppBarTheme(elevation: 0, centerTitle: false),
      inputDecorationTheme: const InputDecorationTheme(
        border: OutlineInputBorder(),
        isDense: true,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          // A fixed minimum, never Size.fromHeight: an infinite minimum width makes a filled
          // button inside a Row fail layout and vanish while its hit area spans the row. A
          // full-width button gets it from its parent, as the login form's stretched column does.
          minimumSize: _buttonMinimum,
        ),
      ),
      // The same minimum for the buttons that stand beside a filled one — the Gmail page's
      // Reconnect and Disconnect, a list's toolbar — so their tops and bottoms line up instead of
      // the outlined one sitting a few pixels short of its neighbour.
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(minimumSize: _buttonMinimum),
      ),
    );
  }
}
