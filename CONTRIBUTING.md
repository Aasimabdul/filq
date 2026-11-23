# Contributing to Fliq

Thanks for helping improve Fliq. The goal is a small, privacy-conscious Android file converter that stays usable on low-memory phones, tablets, and in portrait or landscape orientation.

## Before opening a pull request

1. Search existing issues and pull requests.
2. Describe the user-visible problem and the smallest practical fix.
3. Keep conversions offline when possible; document clearly whenever a server is required.
4. Avoid adding large dependencies, unnecessary permissions, analytics, or background network requests.
5. Never include personal files, secrets, signing keys, access tokens, or server credentials.

## Build and checks

Use JDK 17 and an Android SDK compatible with the project:

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
```

If you have a device or emulator, also test sharing one file and multiple files, successful and unsupported conversions, save destinations, opening/sharing outputs, screen rotation, small screens, and large font/display-size settings. Clearly state which checks you actually ran; do not claim device testing if it was not performed.

## Pull request checklist

- [ ] The change has a clear purpose and is scoped.
- [ ] Build/lint/tests run, or any unavailable checks are disclosed.
- [ ] UI remains scrollable and usable in portrait and landscape.
- [ ] Errors are understandable and do not crash the app.
- [ ] README and conversion limitations are updated.
- [ ] No secrets or user data are included.
