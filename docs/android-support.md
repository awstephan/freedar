# Android support

Freedar supports stable Android versions receiving upstream Android platform
security fixes. **As reviewed on 2026-11-08, the supported range is Android
14–17 (API 34–37).** This is a support policy, not a claim that every version or
device has been tested.

The reference is Google's [Android Security Bulletins](https://source.android.com/docs/security/bulletin/asb-overview),
specifically their **Updated AOSP versions** for platform fixes. The
[November 2026 bulletin](https://source.android.com/docs/security/bulletin/2026/2026-11-01)
lists Android 14, 15, 16 (including 16 QPR2), and 17. Pixel device update
lifetimes, Linux kernel LTS and Google Play system updates do not define this
range. No Google services are required by Freedar.

Review the baseline before each release and at least quarterly. Check current
platform bulletins and any explicit upstream end-of-support announcements;
absence from a single vulnerability's affected-version list is not evidence
of end of support. Record the review date, sources and supported range here.

## Older Android versions

The APK installation minimum remains **Android 8.0 / API 26**. Android 8–13
are **unsupported**: installation is permitted, but compatibility and fixes are
not promised and old-version-only failures do not gate releases. There is no
runtime version block or unsupported-version warning dialog.

Opportunistic checks on older devices remain useful. A passing run does not
extend support: the [Pixel 4 XL / Android 12 record](validation/issue-16.md)
is older-hardware compatibility evidence. Prior API-26 records describe their
original builds, not current reruns. Supported OS status also does not establish
that a particular device has received its manufacturer's security updates.
