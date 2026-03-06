# Stream Keep Alive v1.6

## Highlights
- Installer now prefers the latest GitHub Release APK asset (`StreamKeepAlive-vX.Y.apk`) and falls back to `main` raw APK only if release asset is unavailable.
- Installer now prints APK source URL, SHA256 checksum, expected APK version, installed version, and performs version downgrade protection.
- Added ship helper script: `scripts/prepare_installer_apk.sh`.
- Release workflow now uploads both:
  - `StreamKeepAlive-vX.Y.apk`
  - `StreamKeepAlive.apk`
  - with SHA256 files for both.

## Version
- `versionName=1.6`
- `versionCode=7`

## Shipping
1. `./scripts/prepare_installer_apk.sh --allow-debug` (or signed release env without `--allow-debug`)
2. Commit and push `main`.
3. Tag `v1.6` and push tag.
4. GitHub Actions `release.yml` publishes release assets.
