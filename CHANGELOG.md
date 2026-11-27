# Changelog

Public-facing summaries of the version notes previously kept in the README. Internal test cases and device details are omitted. This is a development history, not a list of published releases.

- **0.1.18:** Added an experimental gesture diagnostic for stale touch state. A gesture callback does not verify recovery; the in-app approach did not reliably resolve the reported symptom. The repository also added a separate local TalkBack speech logger and offline reporting support.
- **0.1.17:** Added a stricter TalkBack-like node filter and an optional swipe traversal mode. Confirm actual announcements separately.
- **0.1.16:** Included visible, meaningful controls even when some are marked unimportant for accessibility. Added synthetic test items.
- **0.1.15:** Added label-checked activation and a single-request lookup/focus/activation path to reduce unintended activation. This is not an authorization boundary.
- **0.1.14:** Improved collection of visible WebView nodes and bounded traversal depth.
- **0.1.13:** Improved text target lookup and match diagnostics; added best-effort web-content hints.
- **0.1.12:** Added test text input, window/dialog detection, selected global actions, and heuristic stop guards.
- **0.1.11:** Documented scrolling sweeps, deduplication, page limits, and WebView hints.
- **0.1.10:** Added a synthetic inline link to exercise link navigation.
- **0.1.9:** Added node inspection and changed the TalkBack-like approximation to follow tree order.
- **0.1.8:** Added a TalkBack-like traversal mode; it does not reproduce every TalkBack decision.
- **0.1.7:** Corrected sweep counting for already-focused nodes and expanded reported node properties.
- **0.1.6:** Added the synthetic `DemoDefectsActivity` test screen.
- **0.1.5:** Added health, first/last and category navigation, bounded sweep and result controls, scroll, and activate/read.
