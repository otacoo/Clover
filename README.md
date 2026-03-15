# ![clover_logo](docs/images/icon_64.png) Clover - imageboard browser for Android

### Download APK: [ [latest release](https://github.com/otacoo/Clover/releases/latest) | [all releases](https://github.com/otacoo/Clover/releases) ]


## About this fork

> [!NOTE]
> This fork is originally based on **Blue Clover**, the repo of which has been deleted by the author, it contains all the changes and PRs merged by that dev since Clover was abandoned (read the file [CHANGES_old_nuudev.txt](docs/CHANGES_old_nuudev.txt)).\
>It also contains some changes made by [EmiriUchi](https://github.com/EmiriUchi/) in [Gold Clover](https://github.com/EmiriUchi/GoldClover/commits/1a6c19eccfc35b56e22d5b87d04d01117b6bc0d4/).

Read the [CHANGES.md](CHANGES.md) file for all the changes made in this fork so far.\
There has been an extensive backend overhaul and modernization of the app while keeping compatibility with older Android versions and the familiar Clover UX style:
- Supports the latest 4chan captcha, 4chan pass, Email verification
- Supports browsing and posting on 8chan
- Supports browsing & posting on Lainchan and Sushichan
- Supports multi-file uploads
- Backup & restore of settings
- Cookie manager
- In-app theme creation
- New dialog for adding filters
- New options (top/bottom arrows, randomize filenames, bottom toolbar & more)

**TODO:**
- Replace Jsoup with a more lightweight HTML parser 
- Improve performance of the app

## About Clover

Clover is a fast Android app for browsing imageboards, such as 4chan and 8chan. It adds inline replying, thread watching, notifications, themes, pass support, filters and a whole lot more. Clover is licensed under the GPL and will always be free.

## Sites supported

- 4chan
- Sushichan
- Lainchan
- 8chan

## Issues and features
Issues can be reported here at the [Issues page](https://github.com/otacoo/Clover/issues).\
Provide as much detailed info as possible, logs, screenshots etc.

Please search before reporting an issue to avoid duplicates!


## Contributing & Building

**Note:**\
I'll accept [pull requests](https://github.com/otacoo/Clover/pulls) for adding [*site support*](docs/site_support.md).

To build and test locally, see the [BUILD.md](docs/BUILD.md) instructions.


## License
Clover is [GPLv3](https://github.com/floens/Clover/blob/dev/COPYING.txt), [licenses of the used libraries](https://github.com/otacoo/Clover/blob/dev/Clover/app/src/main/assets/html/licenses.html).


## Contributor chain

floens - original [Clover](https://github.com/floens/Clover)\
nuudev - [Blue Clover](https://github.com/nuudev/BlueClover)\
EmiriUchi - [Gold Clover](https://github.com/EmiriUchi/GoldClover/commits/1a6c19eccfc35b56e22d5b87d04d01117b6bc0d4/)
