# Create Release

Create a new GitHub release with APK and test IPA.

## Steps

1. Ask user for the version number (e.g., `v0.3.0`)
2. Check current version in `app/build.gradle.kts` (`versionName`)
3. Update `versionName` and `versionCode` in `app/build.gradle.kts` if needed
4. Create a git tag: `git tag <version>`
5. Push the tag: `git push origin <version>`
6. The CI will automatically:
   - Build the debug APK
   - Create the test IPA
   - Create a GitHub Release with both artifacts attached
7. Optionally update the release description with `gh release edit`

## Notes
- The `build.yml` workflow has a `release` job triggered by `v*` tags
- Release artifacts: `app-debug.apk`, `TestApp.ipa`
- Use `gh release view <version>` to verify the release was created
