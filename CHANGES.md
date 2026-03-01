## 2026-03-01 – v3.1.3

- **New feature:** Reworked the **Theme** section; you can now create your own themes within the app!
  - Custom themes can be created and deleted
  - Custom themes will be backed up and restored with the backup/restore option
  - Modified a few of the default themes like YotsubaB
  - Added a Reset button to reset the Theme back to its color defaults
  - Added a loading bar to the Theme preview
  - Swiping between themes will now show their FAB colors live
- 4chan: improved the flow and toast information for the captcha
- 4chan: added preventive measures for when cookies/session get stale which could lock the user in a cooldown loop
- Color picker now allows one to pick custom colors on top of the usual presets
- **New feature:** Backup & restore option now allows you to pick which keys to restore
- Fix an issue where thumbnails with odd dimensions could cause a crash (#1)
- Fix an issue where selecting text could cause the app to crash on certain Android versions (unconfirmed #1)
- Dev options: Fix a bug where the cookie add/del dialog was stacking dialogs
- Dev options: Fix an issue where certain 4chan cookies couldn't be deleted

## 2026-02-27 – v3.1.2

>[!WARNING]
>A lot of old back-end code was rewritten in this release, if something broke, I'm sorry.\
>Please open an [issue here](https://github.com/otacoo/Clover/issues).\
>You can tap the Clover build number in the app a few times to open dev settings, there you will be able to export the app's logs.\
>Thank you for your understanding.

- **New option:** Add Top and Bottom buttons (Appearance > Layout)
- **New option:** Add Auto (System) theme to match Day/Night cycle theming
- **New feature:** 4chan: support Shift JIS and Mona font
- 4chan: Improve captcha UX and fail-safe for future captcha shenanigans
- 4chan: Formatting tags will now correctly show up only if the board supports them
- 4chan: Fix an issue where the board list could become stale and never be fetched again on a fresh start if there was no connection
- 4chan: Captcha view will now use Theme matching background and text color
- Bring back Lainchan support
- Update Sushichan domain to .cafe
- Vichan engine updates
- Remove 8chan site support for now until I can get it working properly
- Clover will now only fetch boardlists when opening the add‑boards dialog in Settings > Site (press the “+”); no network activity on app start
- Changed a few default settings (don't be alarmed if some things get enabled/disabled)
- Backup JSON will now have the hour in the filename when saving
- Backing up settings will no longer change Clover's "Save location" on some Android versions
- Fully moved the app from Volley to OkHttp, refactored a lot of "old" code in the process
- Fix an issue where scroll position wasn't being properly saved when leaving a thread or the app
- Tentatively fixed an issue that could cause the app to crash when selecting text in a thread (issue #1)
- Move image saving from the main UI thread to avoid slow downs when saving images
- Dev options: Logs are now anonymized and can be exported depending on selected filter

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
