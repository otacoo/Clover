# Changes

> [!NOTE]
> This fork is originally based on **Blue Clover**, the repo of which has been deleted by the author, it also contains some PRs merged by that dev since Clover was abandoned (read the file [CHANGES_old_nuudev.txt](CHANGES_old_nuudev.txt)).\
>It also contains some changes by [EmiriUchi](https://github.com/EmiriUchi/) in [Gold Clover](https://github.com/EmiriUchi/GoldClover/commits/1a6c19eccfc35b56e22d5b87d04d01117b6bc0d4/).\
> It retains the Blue Clover icon but changes the app name back to **Clover**.


## Build System & Environment
- Upgraded Android SDK to version 34.
- Upgraded Java version to 17.
- Upgraded Gradle Wrapper to version 8.0.
- Fixed lots of small bugs and outdated stuff that made building the app difficult.
- Target Android version of the app is **Android 10 (Q)**.
- Only tested working on Android 10 and 11 so far.
- Revert versioning back to its original three digits 3.22 -> 3.0.22

## Site Management
- Added support for the new 4chan captcha.
- Added a Verification section for 4chan to verify email and get the associated token.
- Automated 4chan/8chan setup on first launch.
- Removed support for Lainchan, Dvach.
- Improved database cleanup for orphaned sites.
- Restricted browsing list to sites with at least one added board.
- Simplified setup UI by removing the manual site adding/removing buttons (not planning on Play store).
- Add full support for 8chan and its POWBlock.
- Modify dialog for how boards are added for infinite dynamic boards like 8chan.

## Video Player & Media
- Switched to Media3 ExoPlayer from the old ExoPlayer AAR.
- Added a "Video Player" settings group with a configurable "Player controls timeout" in seconds.
- Integrated native Media3 playback speed picker.
- Fixed the "Permission Denial" errors by implementing `ClipData` for external video and sharing.
- Fixed shared files using internal hashed names instead of original filenames.
- Fixed a saving bug that broke filename extensions when names differed only by casing.

## Theme & UI Customization
- Added dedicated settings for Accent and Loading Bar colors.
- May have broken one or two theme things in the process.
- Add toggle for checking for updates instead of silently checking.
- Add option to have the toolbar at the bottom.

## Backup
- Added a new "Backup & restore" section to main settings.
- Saves current app settings as well as cloudflare, email verification keys etc. for easy backup & restoring.

## Bug Fixes & Stability
- Fixed launch crashes caused by missing site class mappings during initial setup.
- Resolved compilation errors related to `R` package references.
- Updated a few variable and parameters to match newer Android Sdk conventions.
- Fix bug with theming not applying correctly on certain threads after a theme switch.
- Fix bug with certain links in quotes, particular for vichan, not being linkified.

## Misc
- Removed NFC sharing and associated permissions.
- Add button in dev settings to clear WebView localStorage or cookies.
- Removed ponies.
