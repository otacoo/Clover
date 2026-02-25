## 2026-02-25 – v3.1.1

- Filters will now be correctly backed up
- Fix a threading and UI sync issue with the swipe deleting of filters
- Better handling of in-app update check and fail

## 2026-02-24 – v3.1.0

- Add option to select Clover's icon flavor (Blue, Green or Gold)
- Rework the Filters menu
    - The add filter dialog is now scrollable
    - Filters now have an order, filters at the top take precedence
    - Filters can be re-ordered
    - Turned FABs into regular buttons so they don't hide the filter list
    - The Pattern field should no longer lag as much by pattern matching on every keystroke
- Add support for [code], [math] and [eqn] tags in the posting form
- Add option to always show tags in posting form under Behaviour > Reply
- Board lists will now be sorted alphabetically (or as they come out of the API)
- Fix bug where pinned thread's unread count wasn't being properly synced with the thread
- Fix bug where the last read post wasn't being properly managed
- Downgrade Gradle and AGP to more stable and compatible versions

## 2026-02-24 – v3.0.28

- 4chan: Add option to view all captcha challenges in a single view
- 4chan: Fix Expiry cooldown margin
- 4chan: Slightly increased captcha instruction font size
- Pressing "Back" during captcha will no longer lose the captcha
- Dev options: Add option to add cookies for 4chan
- Dev options: Fixed a bug where deleting or updating cookies wasn't being correctly applied
- Clover update button now is both an update trigger and a toggle
- Reduced APK size
- Updated WebView and OkHttp packages to newer more stable versions
- Rainbow went home

## 2026-02-23 – v3.0.27

- Fix for an omission where captcha toasts weren't being gated by the "Cooldown toasts" toggle

## 2026-02-22 – v3.0.26

- :warning: Allow spur.us and mcl.io to set cookie on 4chan so the captcha challenges can load (read /g/thread/108210452)
- Add full support for cross-board catalog links
- Fix issue with user's posts not being marked (2x)
- Fix issue with Mark as my post (same problem as above, proguard issue)
- Fix a pesky bug with the storage permissions
- Improve log viewing and filtering in the dev options
- Allow to export logs
- Add option to view and edit 4chan cookies (dev settings)
- Add button to check for app database integrity (dev settings)

*Note: To access dev settings requires touching the Clover build number 3 times*

## 2026-02-22 – v3.0.25

- Fix bug with user's posts not being marked
- Add more toast feedback during 4chan captcha fetching

## 2026-02-22 – v3.0.24

- Initial otacoo release

# Changes

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
- Add support to load and read 8chan and its POWBlock *(still working on posting and captchas and multi-image upload)*.
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
