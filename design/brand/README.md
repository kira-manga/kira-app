# App Brand Assets

`kira-logo.svg` is the canonical in-repository copy of the mark used by
`kira-web/public/assets/brand/kira-logo.svg`.

Platform assets derived from it:

- Android adaptive foreground and monochrome layers:
  `app/src/main/res/drawable/ic_launcher_*.xml`
- Android legacy launcher fallbacks:
  `app/src/main/res/mipmap-*/ic_launcher*.webp`
- iOS Light, Dark, and Tinted app icons:
  `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/`
- iOS appearance-aware launch assets:
  `iosApp/iosApp/Assets.xcassets/LaunchLogo.imageset/` and
  `LaunchBackground.colorset/`

Keep the Android foreground inside the 66 × 66 dp adaptive-icon safe zone. Keep iOS app icons at
1024 × 1024 px and do not add rounded corners; the operating system applies its own mask. Launch
screens must remain static and lightweight—no text, network work, or artificial delay.
