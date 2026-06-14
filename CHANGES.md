## 2026-06-14 – v3.6.4

- Filters: Fix an issue where *Apply to OP only* modifier was only working for WATCH or PIN actions

## 2026-06-10 – v3.6.3

>[!NOTE]
>It's not just you, it seems the Email Verification cookie is being *ignored* (dropped?) all of a sudden?\
>I have not been able to identify if it's Clover causing this or if it's coming from 4chan; despite the 60s cooldowns and harder captchas... posting still works as normal.\
>I deleted my 4chan_pass cookie, went through the Email Verification process and started getting easier captchas again, this leads me to believe it's coming from 4chan itself but I cannot confirm with 100% certainty.

### 4chan Cookies
- Added some missing attributes to the email verification cookie (`4chan_pass`) (unlikely to be the cause)

### Cookie Manager
- Clicking a cookie in the Cookie Manager now shows its expiration date below the value edit field
- Cloudflare cookies set directly by WebView (4chan captcha) will show `Unknown (WebView)`

### Filters
- Add a `Remove All` button to the Filters, requires a 2-second hold to confirm
- The filter backup/restore system now correctly exports and imports the `onlyOnOP` field
- Created a tool to convert 4chanX filters to Clover: [Click here](https://otacoo.github.io/Clover/tools/filter_converter.html).

### Captcha
- Single page captcha: the Submit button now scrolls into view after selecting the final answer

## 2026-05-31 – v3.6.2

### Proxy, DNS over HTTPS, & Custom User-Agent (issue #32)

Read [issue #32](https://github.com/otacoo/Clover/issues/32), the tl;dr is that these options were leaking during WebViews and DNS over HTTPS has been removed.

**Proxy:**
 - **New:** Add support for Proxy authentication (username & password)
 - Added an info dialog before enabling the proxy
 - Fixed issues where the Proxy was leaking the users IP when using WebViews (4chan captcha, Email Verification, POWBlock et al)

**DNS over HTTPS:**
  - Removed the option.
  - DoH does not work natively with WebViews and was leaking the users DNS when using WebViews. This type of proxying is best used at the system level

**Custom User-Agent:**
- The Custom User-Agent will now correctly apply during WebViews
- Changing the Custom UA requires an app restart

### 4chan
- Fixed an issue where the `4chan_pass` cookie was inadvertently being stored as a session cookie, causing it to periodically expire and trigger harder captchas

### 8chan
- The interactive OP data will be now skipped in /gacha/ threads
- Fixed an issue where hard POW hashes could fail
- Fixed an issue where doing the 8chan verification or accepting the TOS would wipe out all 4chan cookies
- Ensure the Proof-of-Work (POWBlock) challenge responds correctly on fresh installs and properly sequences its snackbar

### Appearance
- Darkened switch button colors for the Light theme (issue #33)
- Fixed missing or badly contrasting color values for the Creamy, Sapporo & Zenburned themes to improve text readability

### Misc changes & fixes
- Fixed an issue with the *Bottom Toolbar* option leaving a big gap at the top when opening the search tab
- Raised the minSDK back again (24)
- The board list snackbar will now push the Floating Action Button (FAB) up instead of overlapping it
- Fixed an issue with clearing the previous background text selection when tapping elsewhere (issue #31)


## 2026-05-24 – v3.6.1

- The reply badge can now be enabled for the regular image viewer, not just the album.
  - The toggle is shared and will also appear in the image menu options

#### Gestures:
- Fixed a typo that caused the Swipe gestures to not work
- New option: Double-tap to play/pause


## 2026-05-24 – v3.6.0

>[!NOTE]
>This version has changed the database to add a new filter option.\
>I suggest doing a backup of your settings *before* updating, then doing a backup again with the new version if everything works correctly.
>
>I now consider Clover **"feature complete"** and will likely not be adding new features or options, only bug fixes.\
>Thank you for your support and understanding.

### Thread Watcher
- Refactored the TW menu
- Moved *Highlight currently open thread* option here

### Appearance
**New themes:** Zenburned, Tranquil
- Refactored custom themes
  - Themes are now based off the default themes
  - Allows to pick any theme as base theme
  - Tapping and holding a color will reset the color back to theme default
- Tone down the "own reply" highlight for the three main themes (light, dark, black)
- Fixed an issue where switching between themes didn't correctly refresh certain layout elements
- Fixed an issue where the unread line color for custom themes wasn't applying (issue #31)

### Gallery
**New option:** Show/hide reply badge
 - The reply badge will appear in the image viewer for posts that have >= 2 replies
  
**New option:** Column count
- Pick between 2 and 4 columns

- Fixed a bug where thumbnails weren't loading in the background when swiping through images in the image viewer (issue #31)
- Removed the duplicate *Download album* entry from the menu (issue #31)

### Bottom toolbar & reply
- Fix issues with the layout not responding to the bottom toolbar, especially during the 4chan captcha or reply view (issue #31)
- The board list will now be inverted when using bottom toolbar (issue #31)
- The board list spinner's arrow will now point in the correct direction (issue #31)
- The search input will now correctly show up in the board list spinner
- The reply FAB button pencil will point down again when using bottom reply (issue #31)

### Filters
- Added an *Apply to OP* modifier checkbox
  - Will be greyed out automatically when not applying
- Added a new action: **Pin post**
  - This option will pin and highlight threads at the top of the catalog / board mode and only applies to OP
- Watch post action will only apply to OP

### Misc changes
- Video thumbnails will now be decoded and appear in the reply view
- Improved video initiation a bit when using VP9 decoder (note: if videos take a few seconds to start, try setting the decoder to *System only* and use the WebView fallback)
- Appended (default) to 100% Thumbnail Scale (issue #31)
- The UI will now refresh after changing thumb scale or font sizes
- Increased number of items in history by 20 (cleanup happens at 120, which trims them to 70 items)

### Other Fixes
- Fixed an issue where certain file pickers (e.g. Fossify) weren't being given the correct intent
- Fixed an issue where taps where going *through* the left and right drawers when opened (issue #31)
- Fixed an issue where Swipe gestures weren't being correctly greyed out after selecting up or down for one option (issue #31)

Thank you @tetra65 for giving me so much work


## 2026-05-20 – v3.5.3

**New old option:** Swipe while zoomed in

### Catalog search
- Catalog scroll position will now be stored and restored after a search (issue #30)
- Pinned search & cross-board links (issue #29)
  - The bookmarks drawer can now be opened
  - The search can now be cleared to browse the board

### Misc changes & fixes
- Reduced minSdk to 23 to allow installs on Android 6.0 Marshmallow
- Removed some ridiculous width values on the add board dialog that could cause a crash
- Set exclusions on system gestures for newer devices so the drawers can be used without triggering system navigation **(UNTESTED)**
- Remove crash reporting dead code

Thank you @T-W-A-I-N

## 2026-05-18 – v3.5.2

### Media
**New feature:** Added a rotate button to the video player controls
- Load full size thumbnails: Improve image loading from the cache so that the image isn't downloaded a second time when viewing the image in full
- Derpibooru and Furbooru reverse search options will only show in /mlp/ and /trash/

### 8chan
- Added fixes for the new POWBlock update on .st
- Fixed Auto domain (failover) not working properly and silently always switching to .st

### Misc changes & fixes
- **New feature:** Long tap a thread in catalog or board mode to show the menu
- Navigation: navigation history will now be kept and restored when navigating to and from catalog searches (pinned search or cross-board links) (issue #29)
- Mark as my post: Fixed a bug where it wasn't marking the post visually without a restart
- Catalog: Fix an issue where an unread line was being added to the catalog (issue #23)
- Catalog: Changing catalog column count will now be applied immediately without a restart
- Top and bottom FAB: Further fine tune the top/bottom FAB buttons timers

## 2026-05-16 – v3.5.0

### Snackbars
- Refactored all the snackbars to use Material snackbars
- Snackbars will now take on the primary color of the theme
- Floating action buttons (FAB) will better interact and hide when a snackbar is being shown
- Snackbars will no longer appear while the reply view is expanded

### Media
- **New option:** Gestures
  - Pick an action for up and down swipe (save file or dismiss)
  
### Appearance
- **New themes:** Creamy, Sapporo
- The system navigation and status bar will now take on the colors of the theme, works with bottom toolbar as well

### Misc changes & fixes
- Catalog mode: Fix an issue where threads with no comment would have their replies/images anchor move up
- Email verification: all 4chan cookies will now be cleared to correctly set the 4chan_pass cookie during the verification process (issue #28)
- Top and bottom FAB: reduce the delays and doubled how much is required to scroll before they show up
- Reply FAB: shifted it down again so it doesn't hide the last post (issue #26)
- Fixed a long-standing issue where tapping to scroll through a thread would text highlight links like URLs or quotelinks too easily

## 2026-05-14 – v3.4.0

### Media
- **New option:** Load external media links in-app
  - This option will allow catbox.moe and uguu.se links to open within the app's media viewer
  - Supported extensions: .mp4, .webm, .mp3, .ogg, .jpeg, .jpg, .png, .webp, .avif, .apng, .jxl, .gif, .bmp, .flac, .opus, .mkv

- **New option:** Fetch Youtube titles
  - Will replace the URLs with [Youtube] {Title}

### Appearance
- **New theme:** Tako
- **New option:** Bottom reply
- Added unread line to the colors that can be chosen when creating themes
- Adjusted unread line colors for a few themes instead of all using the default red color
- Fixed an issue where the Subject and Name in the dummy post weren't showing the theme's colors
- The toolbar for the image viewer will now also show at the bottom when using bottom toolbar

### Misc changes & fixes
- Improved animations for opening the reply view
- Improved app responsiveness and how the layouts are rendered, reducing background garbage collection
- Caught an error when a thumbnail fails to load which could cause the app to crash (unconfirmed issue #24)
- Fixed an issue where the Verification cookie option was showing a different domain cookie from the cookie manager, preventing the cookie from being cleared (issue #28)
- Fixed the image rotate submenu not being correctly anchored
- Fixed the color picker dialog for the filters being cut off
- Added new image assets to show when a file is deleted and a thread is 404 instead of empty nothing
- More tentative fixes for a red background square showing when a thread is deleted from the catalog (issue #23)

## Other
- Updated the translations
- Updated a few libraries

Thank you @tetra65 @T-W-A-I-N

## 2026-05-06 – v3.3.25

- **Themes:** 
  - Improved color wheel and bar touch surfaces to prevent accidental touches (issue #26)
  - Centered color swatches and removed fully black swatch (issue #26)
- Bottom-most floating menus will now be correctly aligned (issue #26)
- Shift FAB buttons up to avoid appearing behind the new posts toast (issue #26)

- **New option:** Use WebView as fallback player
  - Will try to play video files with the internal WebView when an unsupported video format is found

- **New option:** Disable Back button in thead view (issue #27)
  - Wil disable the back arrow when in thread view so that the bookmarks drawer can always be opened

- **Removed options:**
  - Removed the option for *Buffer before playback*, will use the default device buffers
  - Remove *Enable pinned searches*, will now always be enabled and part of Clover (issue #27)

- Refactored the option *Load full size thumbnails*, will now act similar to the other Media loading options

- Refactored the bookmarks drawer (issue #27):
  - The bookmarks drawer settings buttons will now always be fixed, only the pin list will scroll
  - When using the bottom toolbar, the whole list, including buttons, will be inversed
  - *Note 1:* Swiping down on the bookmarks drawer allows you to fetch new posts
  - *Note 2:* Existing pins in the list will not be inversed when switching toolbars, only newly added ones

Thank you @tetra65

## 2026-05-01 – v3.3.24

- **Album view:**
  - Added a download button to the toolbar
  - Fix a crash that would happen when downloading images into subfolders
  - Improve the process for multi-image downloading so it doesn't lock up the app on download start

- Add guardrails for when a thread is deleted or has stale info in the catalog (issue #23)
- Skipping 4chan pass will now only work when browsing a 4chan thread
- Streamline image loading when browsing threads so images don't fill up the Java heap rapidly whcih could cause garbage collection to trigger constantly or make the app crash
- Fix a bug on certain devices where saving images didn't work (issue #25)

## 2026-04-20 – v3.3.23

- **New option:** Pick Auto (System) themes
  - The burger menu for the Auto (System) theme will now allow the user to pick which theme they want to use for day/night time
- The Black theme can now be picked as base when creating a new theme
- Fix a bug with with the file picker only allowing to pick images instead of all media types
- User posts will no longer count towards the unread count (issue #20)

Thank you @tetra65

## 2026-04-19 – v3.3.22

- **New option:** Load full size thumbnails (Media > media loading)
- **New option:** Show/hide image info (Gallery)
- **New feature:** Skip 4chan pass. Holding the submit button for 2 seconds allows the user to bypass/pause their 4chan pass for that post.
- Small code fixes

## 2026-04-13 – v3.3.21

- **New option:** Tap the quotelink span to open
  - This is actually a fix for the quotelinks opening when tapping anywhere inside the quotelink's span instead of just the quotelink itself which led to a lot of accidental taps. Since some users might be used to this behaviour I added an option under *Browsing > Posts & replies* to gate it.
- Tablet-specific changes:
  - The option *Never hide the toolbar* will now correctly work
  - The settings menu will no longer be shown in a popup when using the Auto layout mode
- New posts snackbar:
  - Re-implemented this snackbar programmatically within the app instead of relying on Android's own snackbars
  - Snackbar should no longer float when coming from wakelock or switching between threads
  - VIEW button will now correctly scroll to the unread line if not in view
  - Snackbar will no longer show at the top when using the *Bottom toolbar* option, can now be correctly shown above toolbar
  - Will try to mimic tablet snackbar size when using a tablet
- Further improve Top/Down buttons (issue #22):
  - Will no longer show the Top button on hitting the bottom and vice-versa
  - Fix an issue where using the Top button would leave a gap in the header
- Fix an issue on certain devices where a retry prompt was shown when updating the app instead of only when it failed to update (issue #21)
- Fix an issue with short URLs in 8chan, Sushichan, and Lainchan, that were being considered as internal links and thus getting the wrong styling

Thank you @T-W-A-I-N

## 2026-04-12 – v3.3.20

- Slighty adjusted the values of the top/bottom buttons to more rapidly respond to direction changes
- Fix an issue where the keyboard wasn't being shown automatically in thread views when doing a search (issue #20)
- New post snackbars will now show at the top of the thread when using the bottom toolbar option instead of being hidden behind the toolbar (issue #20)
- Remove the overboard /kaitensushi/ for Sushichan
#### 8chan:
- The captcha layouts will now allow browsing while doing the captcha instead of taking up the whole screen
- Adjust the captcha layouts when using the bottom toolbar
- Fix certain APNG images not loading in catalog/thread view due to being served as "image/png" MIME type
- Fix text spoilers revealing their contents when used within a greentext

Thank you @tetra65

## 2026-04-08 – v3.3.19

- Improve Clover's cache handling
- Fix an issue where the Thread Watcher notifications were firing every time the user scrolled through a thread (issue #19)
#### **8chan:**
- Improve the layout and descriptions for the 8chan captchas so it better differentiates proof-of-work from per-post captcha layout
- Improve the feedback when the captcha fails as well as layout descriptions
- Tapping the captcha image or reloading the layout will now correctly fetch a new captcha image
- Fix a bypass cookie issue introduced last version that prevented posting on 8chan (issue #18)

Thank you @T-W-A-I-N

## 2026-04-08 – v3.3.18

- Update proof-of-work captcha after 8chan changes
- Fix an issue where the cookie manager was showing cookies from the wrong cookie storage for 8chan
- Fix an issue where the new posts snackbar could be orphaned and get stuck if it triggered during the 4chan captcha process

## 2026-04-06 – v3.3.17

>[!NOTE]
>I suggest re-doing a backup of your settings from the newest Clover version to avoid issues when updating or restoring settings, especially if you use or created a custom theme.

- **New feature:** Clover now supports APNG images
- Fix a crash when *Alternate layout* was enabled
- Fix an issue where the new posts snackbar was getting stuck forever
- Fix an issue where certain Clover preferences, in particular for themes, were being serialized when creating a backup and could cause crashes or ghost themes
- Add the following new color choices for custom themes: Greentext, Highlighted quotelink and Spoiler colors
- *Condensed font* will now correctly apply to the catalog/index view as well as subject, post info and reply anchor

## 2026-03-31 – v3.3.16

- Fix an issue where notifications for saving media failing to fire could make the app crash occasionally
- Improve the dummy post in the Themes section (issue #17)
- Fix an issue where the boardlist wasn't fetched correctly on startup, preventing the boardlist drawer and formatting tags from working correctly (issue #16)

## 2026-03-30 – v3.3.15

- Fixes for issue #13: [Thread watcher] Unread count doesn't decrease to 0 consistently
  - Fix an "issue" where the thread watcher unread counter for a thread would update before the posts where shown/notified
  - Fix an issue where posts were being wrongly counted as *read* on large screens
  - Fix an issue where scrolling to the bottom in a thread wouldn't reset the unread counter properly
  - Fix an issue where the snackbar wasn't being shown when switching between threads or entering a pinned thread
  - The unread counter for a thread will now only show the number of posts that were not yet seen by the user, when coming from 0, e.g. thread updates with 5 new posts -> 3 posts are in view, 2 are off-screen -> unread count will only show 2 instead of 5
  - Improve how the unread line reacts to changing threads and fetching new posts
- **8chan:** Cookies will no longer be synced on startup if no boards for 8chan were added to the boardlist
- **4chan:** Email verification cookie (4chan_pass) will no longer be backed up with the app's preferences; will still auto-sync if it exists in the cookie store/manager including from a cookie backup
- Fix an HTTP connection leak where images could stop loading the more images were opened and the longer the app was used
- Cache and quiet down some background logging

## 2026-03-27 – v3.3.14

- Fix an issue with the boardlist drawer search function dismissing certain keyboard keys like numbers or delete
- **4chan:** Fix an issue where 4chan cookies where being intercepted where they shouldn't preventing usage of *4chan pass*
- Tentative fix for issue #13 again where unread count isn't properly reset when at the bottom of the thread, will now try to reset when the last post of the thread is in view, regardless of anything else
- Refactor and remove a lot of deprecated Android code for how storage works
- Improve the downloading and notification of media elements
- Fix an issue where the *Save location* in the settings could get lost on app restart
- Fix an issue where the new posts snackbar could get stuck forever if another snackbar was shown at the same time

## 2026-03-24 – v3.3.13

- Use better detection for 4chan pass user's cookies
- Threads opened by cross-links will now correctly get their titles in the toolbar
- Remove the spring animation introduced last version when the new posts snackbar is removed from view
- Fix an error on newer Android versions where floating menus weren't being correctly dismissed
- Add fixes for edge-to-edge on newer Android versions preventing unread count from firing correctly
- Fix a bug where threads with only one post (OP) weren't being properly reset to 0 in the watcher

## 2026-03-23 – v3.3.12

- Clover will now try to load thumbnails and titles for bookmarks from the cache before trying to fetch them from the internet
- Stagger bookmarked thread updates so they don't fire all at the same time, which could trigger anti-spam measures (1 fetch/sec)
- Fix an issue with *Immersive mode* where images would close when tapped instead of showing the controls
- Fix an issue where the snackbar indicating new posts would appear in the middle of the screen instead of being flush at the bottom, snackbar will now smootly fade in and out
- Fix an issue with the new posts snackbar where it disappeared when scrolling then immediately showed up again
- Remove the RECOVER button from the snackbar on post successful
- Tentative fix for issue #13 where unread count isn't properly reset when at the bottom of the thread
**8chan:**
  - Add support for Story Spoilers on /gacha/
  - Clover will now correctly show a snackbar informing the user is being rate-limited instead of just the regular "POWBlock check failed" 
  - Fix an issue introduced in the last version where the reply anchor could show in the middle of the post for OP
  - Lazy load 8chan thumbs to better prevent rate-limiting (should not be perciptible during regular browsing)

## 2026-03-22 – v3.3.11

- Bookmarked thread updates will now be fetched after 1 sec on startup or when the app comes to the foreground, after that the usual 15 sec update takes over
- Fix for post reply anchor when a post has multiple images, or is using the *Alternate layout* option
- Catch 4chan pass error when it's rejected by the site
- Fix an issue with the database that could cause an app crash
- Update locales

## 2026-03-21 – v3.3.10

- **New option:** Enable localization
  - Added machine translations for German, Spanish, French, Italian, Japanese, Portuguese, Russian
  - Applying the localization correctly requires a *full* app closing and restarting
- Improve image loading and retrying if an image fails to load
- Fix an issue where all boards where getting added to the Pinned Searches list if no board was added for the site
- Fix an issue where video controls weren't showing up for spoilered videos
- **8chan:**
  - Removed the .cc domain
  - Improve response to rate-limiting and how the app deals with changing domains when using Auto (failover)
  - Fix an issue with POWBlock check always using SHA-256

## 2026-03-19 – v3.3.9

- **New option:** Highlight currently open thread (Browsing)
- **New feature:** Clover will now ask which file picker a user wants to use (can be reset under Media options > Default file picker)
- **4chan captcha:**
  - Add an option to skip to the next captcha challenge automatically
  - Add a back button in single page captcha mode
  - Fix an issue where hiding the keyboard during the captcha could make the app crash on certain devices
  - Add mitigations for "You seem to have mistyped the CAPTCHA" error... again, a big thank you to cookie anon (@ling-mjg) for putting up with me (issue #11).
- **8chan:** Fix an issue where domains where hardcoded for `.moe` instead of respecting the forced domain option *(Note: POWBlock passing is finnicky due to constant changes to how POWBlock operates, this will have to be rewritten in one of the next releases but for now it works for all domains)*
- *Tap post number to reply* will now correctly scroll to the bottom of the posting form when an image has been picked
- Fix an issue where the *Remove filename* was breaking the file's extension and preventing upload
- Fix an issue where devices using custom overlays or themes could make the app crash (issue #12)
- Fix an issue where posts were not hiding instantly
- Fixed easter egg triggering on every touch
- Misc adjustments and cleanups

## 2026-03-15 – v3.3.8

- Clover now supports adaptive Android icons
- Fix search having a big gap when using the bottom toolbar
- Tapping a post number to reply will now scroll the thread to that reply
- **Cookies of hell:**
  - After a deep dive and thanks to cookie anon (@ling-mjg) for pointing this out (issue #11), Clover should now correctly use the 4chan_pass cookie (email verification) when requesting captchas
  - Created a unified 4chan cookie storage and simplified how cookies are synced between the various systems
  - Added more robust deleting of cookies for the Cookie Manager (except for *cf_clearance* cookie that can't be deleted unless *Clear all* is used)
- **Email verification:**
  - Improved how the webview is created and which cookies are deleted, now only clears Cloudflare cookies for a fresh start
  - Add buttons to refresh the page and browse to any URL just in case
  - Improve the dialog for setting the cookie
- **4chan captcha:**
  - Cloudflare turnstile will now use its native dark theme, we no longer inject anything
  - Thanks to the new unified cookie storage users should no longer run into "You seem to have mistyped the captcha" errors


## 2026-03-14 – v3.3.7

- Fix an issue where the 4chan Email verification couldn't set any cookies
- Improved 4chan_pass cookie syncing and preservation between the various options
- **New option**: 4chan_pass cookie
  - Added an option to set the pass cookie directly under Sites > 4chan (if you missed the cookie manager)
- **New option:** VP9 decoder
  - Allows to set different strategies for the VP9 decoder
  - Add an option to set the video buffer before playback manually
  - Recompiled the VP9 decoder, it should now support VP8, VP9, VP9 10-bit Profile 2
- Increase default video buffers before they start playing
- Bookmarks drawer buttons will now be fixed to the bottom when *Enable bottom toolbar* is enabled
- Clover will no longer connect to a site if no boards for that site were added
- Fix an issue where the app could crash when opening and image then switching to a different view very fast
- Fix an issue with Storage permissions throwing silent errors

## 2026-03-14 – v3.3.6

- No changes, just to test updates work

## 2026-03-14 – v3.3.5

- Increased video buffer size before a video starts playing, should help with stutters
- Fix an issue with cross-board links not working on newer Android versions

**8chan:**
- Improve the syncing and storing of 8chan cookies, should no longer run into "Received HTML instead of JSON" error
- Proof-of-work captcha now has an animated button to indicate it's working
- Add support for picking board flags and a few formatting tags
- Fix an issue where picking files for 8chan only allowed to pick images or videos

**4chan:**
- Images can now be spoilered
- Formatting tags will now correctly appear per-board on startup

## 2026-03-13 – v3.3.4

>[!WARNING]
>About `Posting from your IP range has been temporarily blocked due to abuse`.
>
>4chan seems to currently be banning certain mobile fingerprints (as per Anon on /g/, unconfirmed).
>I do not know if the recent Clover changes to the captcha are at the origin of this ban due to the way the captcha was constructed.
>The changes in this version should prevent this going forward. My apologies.\
>If you get this message and still want to post for now the best solution is to verify your email.
>Keep in mind when you receive the email that it needs to be opened WITHIN Clover (use the option *Enter verification token*), not your regular browser.\
>Copying your *4chan_pass* cookie from another PC or device should work as well.

- 4chan captcha: Clover will no longer set the headers when asking for a captcha, will use devices headers
- Update ExoPlayer to the latest version
- Update VP9 decoder and the way decoders are selected for playback in Clover
  - Set VP9 MIME types as unsupported → this forces device to fallback to hardware decoder → if HW decoder errors out, we use our VP9 decoder
- Fix an issue with videos having a small delay before they became available to play
- Reduced minSdk to 24 (Android 6.0 Marshmallow) so Anon can use Clover on his old phone
- Cleaned up gradle build and added some mitigations for using Clover on older (and newer) devices

## 2026-03-12 – v3.3.3

- Trimmed the VP9 decoder by ~1.3MB, Clover APK now half the previous size
- **4chan captcha:** further refinement of the flow again
  - Will no longer nuke everything when it detects stale fingerprinting
  - Will no longer clear the localStorage to avoid running into 4chan abuse issues
- Gave the posting form some slightly bigger buttons
- Added a title for long-press to post from an URL (yes, this is a thing that existed in Clover)
- Fix issues with the bottom toolbar cutting off certain views like the 4chan captcha
- Force media files to be better recycled from memory
- Possible fix for posts in a pinned thread going into a different pinned thread
- Add safety checks for restoring a backup: will only accept JSON files now
- Add a signature key to Clover backup files so they can be identified instantly by the app
- Fix an issue where navigating back (<-) while the thread was being recycled could cause a crash (issue #9)

## 2026-03-12 – v3.3.2

- Refactor and simplify some of the 4chan captcha code
  - Error messages should be correctly shown (e.g. "Temporarily banned from posting from this IP") instead of redirecting instantly
- Improve the 8chan flow
  - POWBlock should be solved automatically in the background when it detects X-PoW-Status: required header (Acid)

## 2026-03-11 – v3.3.1

- Improve the flow for 8chan on a fresh app start
- Clover will no longer connect to 8chan on startup
- Fix an issue with the 8chan POWBlock cookie not being detected

## 2026-03-11 – v3.3.0

- **New feature:** Add full support for 8chan (catalog, threads, posting, POW bypass, captchas)
  - Add an option to force 8chan to use a particular domain (.moe, .st, .cc) (issue #7)
  - Supports DOOM, SRZ BIZNIZ, pink text, flags
  - Detects when user is being rated-limited or needs to pass POWBlock
  - TODO: various posting shenanigans like post form tags, certain text formatting, automatic POWBlock bypass, better flow and issue detection
- **New feature:** Hide flags (issue #6)
- **App performance (on-going):**
  - Improved background/main thread separation and loading responsiveness, Clover should feel a bit snappier
  - There are still improvements to be made but will require some refactoring
  - TODO: trim down the VP9 decoder if possible, replace JSoup, improve background parsing of posts
- Adjusted media fade in/out animation to be faster
- Add a *Watch* action to the Filter, will only work if only Comment is selected, this action will automatically pin any threads that match the filter
- Improve scroll position saving (issue #8)
- Improve how the unread line is inserted and cleared (issue #8)
- Improve how (You)s are cleared from the bookmarks (issue #5 and #8)
- More dialogs will now correctly use the Theme's background color
- Seeking videos will now be more responsive and less laggy by using NEAREST KEYFRAME instead of EXACT
- Add possible mitigations for posts going into the wrong thread after the captcha flow (issue #8)
- Fix an issue with the Dark Mode on older devices aggressively trying to apply dark mode to the captcha, causing repeat reloads
- *4chan captcha:* fix an issue where a user could get stuck in a loop of "you mistyped the captcha" errors
- *4chan captcha:* toasts will no longer show up for errors if the option is disabled
- Snackbar (notifications at the bottom) button text will no longer follow theme color so it doesn't blend in and become invisible
- *Dev options:* Checking database integrity will now clear duplicate posts from the DB
- Misc small changes like wording and error messages

## 2026-03-09 – v3.2.1

- **Crash on startup:** Fix an issue where the app was missing database entries for the new **comment draft** feature
- Toasts on certain devices should now be properly styled according to Dark/Light mode
- Fix an issue where certain preferences dealing with Sites weren't being backed up
- Fix an issue where pinned searches weren't being properly backed up and restored (showing >>>/null/null)
- Improve VP9 codec

## 2026-03-09 – v3.2.0

- **New feature:** Clover now ships with a VP9 decoder
  - Increased the APK size to ~9MB
  - Clover should now be able to play VP9 WebMs if your hardware doesn't support VP9 or is buggy
  - If it crashes with VP9 videos, please open an issue with the logs
- **New feature:** Clover will now save a draft of whatever is written in the posting form (thread specific) when closing the app
- Video ExoPlayer
  - Will now better catch player errors instead of crashing the app
  - Swiping from a video to another will no longer keep the previous video playing in the background
  - Gave the seekbar more touching space
- 4chan: added a /ban/ entry to the boardlist to check for bans right from the drawer
- 4chan captcha: fixed an issue with certain phones forcing dark mode on the captcha, making the app crash
- 4chan captcha: fixed an issue where a user could get stuck in an infinite loop of refreshing captchas
- Floating menus will now use the Theme's background color
- Bottom toolbar option will now correctly allow refresh by swiping down
- Smaller images can now be zoomed in
- Improved the "Immersive mode" viewer
  - Opaque overlay
  - Smoother hiding of toolbar
  - Now shows useful buttons when touched
- Improved or removed certain animations
  - Smoother image viewing fade in/out
  - Smoother menus and drawers
  - Faster thread loading
  - Removed animation for thumbnail loading
  - Fixed certain animations flashing briefly
- Rewrote certain strings for better clarity
- Fixed a bug where the app could crash when restoring backups from older (or newer versions)
- Fixed an issue where upon restoring a backup certain board descriptions in the drawer would be lost
- A bunny has dropped an egg

## 2026-03-05 – v3.1.5

- **New option:** Pinned Searches (under Appearance)
  - Adds a + button to the bookmarks drawer to pin catalog searches (not compatible with Sushi/Lain sites)
- Cross-board catalog searches (e.g. >>>/a/example) will now correctly show the catalog view
- Posting on Sushichan should now work correctly without errors (issue #3)
- 4chan captcha:
  - Captcha challenge buttons will now follow theming
  - Task images will now preserve their aspect ratios instead of being stretched
  - Fix for task instruction image not being zoomable in single page view
  - Remove confusing blue border when a challenge is hovered
  - Better native 4chan error handling when captcha fails
- Improve performance of the app
  - Media viewer and drawers should now feel more responsive
  - Videos no longer briefly flash before playback begins
  - Better separation of UI and background threads
  - Quieted down unnecessary backgroung logging
- Themes: Fix for the loading bar option not applying the user's color choice
- Adjusted some of the Settings' descriptions
- Minor backend fixes & annoyances

## 2026-03-03 – v3.1.4

- **New option:** Randomize Filenames
- **New option:** Cookie manager (under Misc)
  - Allows one to view, edit and delete cookies for all supported sites
  - Similar option removed from the dev settings
- Vichan: posting and multi-image uploads should now work on Sushichan (working) and Lainchan (untested) (#3)
- Posting form now supports multiple files
- File picker now allows to pick multiple files at once if the Site supports it
- 4chan: further improvements to captcha flow, toasts and ways to reset
- 4chan: Task image in the captcha can now be zoomed in for better reading, if touched
- Small fixes for the Light/Dark themes and certain dialogs
- Fix an issue with the Webview silently crashing because of running in a background thread
- Overhauled or removed deprecated and unused Clover code

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
- Fix an issue with the Auto (System) theme option using the wrong theme colors
- Fix an issue where thumbnails with odd dimensions could cause a crash (#1)
- Fix an issue where selecting text could cause the app to crash on certain Android versions (unconfirmed fix for #1)
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
