# Incoming photo spoiler — Knot 3.0.0 port

Based on upstream Knot v3.0.0 (2b-zipper/Knot commit `af7b87f`).

## Implementation

- Disabled by default; enable in Knot → Chat → Media and restart LINE.
- Received photo thumbnails in chat are hidden by an opaque, tappable cover before their first visible frame.
- SQLite classification runs on a bounded single-thread executor. Unknown records and outgoing photos are not intentionally concealed after classification.
- Rebinding recycled message views removes stale covers and ignores obsolete worker results.
- Disclosed messages stay visible during the current process session (LRU limit 2,048).
- Covers are real FrameLayout children instead of untouchable ViewGroupOverlay objects.
- Native LINE click listeners are left untouched. Unknown UI hierarchies fail open.

## Limitations

- **Not yet built or tested on-device.** Mapping, database schema and view hierarchy must be verified on the installed LINE version.
- Photos inside dynamically loaded carousels/multi-photo messages may need a separate bind hook.
- Only chat thumbnail views are covered, not notifications, galleries or full-screen media.
- Very short provisional masking of other large images may be visible during classification.
- This is a convenience feature, not a privacy/security guarantee.

## Manual QA (required before merging)

1. Enable setting and restart LINE. Verify incoming photo is fully hidden before its first displayed frame.
2. Tap to reveal, then tap again to open LINE's original photo viewer.
3. Check own outgoing photos, stickers, GIFs, video, URL previews and quoted images.
4. Check group chats, multiple attached photos, horizontal carousels, fast scrolling and row recycling.
5. Check disabled mode, themes, large fonts, TalkBack and split-screen.
6. Review Knot logs for mapping/DB failures and test performance/ANRs.

## Local build

Requires Android SDK (compile SDK 37) and a working JDK/Gradle environment:

```bash
./gradlew :app:assembleDebug :app:spotlessCheck
```

A differently signed module APK cannot normally update an existing installation in place; back up configuration before changing signing keys.
