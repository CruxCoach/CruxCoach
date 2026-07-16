# Project Name and Fork Branding

In this repository, **CruxCoach** and its orange interlocking-C logo identify
the upstream open-source Android board app maintained at
https://codeberg.org/CruxCoach/CruxCoach.

The source code and artwork are licensed under GPL-3.0-only. That copyright
licence permits copying, modification, and redistribution; it does not by
itself make a modified build an upstream build or authorize claims of upstream
endorsement. This document records requests intended to keep project origin
clear. It does not determine trademark registration, ownership, geographic
scope, priority, or the rights of any independent same-name user.

## Same-name projects

Other climbing products independently use the name “CruxCoach”, including
https://cruxcoach.app/, https://cruxcoach.com/, and
https://cruxcoach.vercel.app/. This project is not affiliated with, endorsed
by, or sponsored by any of them. Here, “CruxCoach” means only the open-source
Android board app at cruxcoach.org and the Codeberg repository above.

No first-use date, clearance conclusion, or legal priority over those
independent products is asserted here. Anyone planning a public downstream
brand remains responsible for assessing the name they choose in the places
where they distribute it.

## Uses that preserve clear origin

- Refer to this project by name in documentation, comparisons, tutorials,
  reviews, talks, and other discussion.
- Link to the upstream repository or distribution listing using the upstream
  name or logo, with an accurate destination and no endorsement claim.
- Redistribute an unmodified upstream APK with its provenance and signature
  intact.
- Build and modify the app for personal development, testing, or education.
- Use screenshots to discuss the upstream app, subject to the rights of any
  third-party content visible in them.

## Public downstream distributions

For a modified APK offered through an app store, public download, or other
broad distribution, upstream asks the distributor to use a distinct name and
icon and to identify the build as a fork. “Based on CruxCoach” is welcome when
it links back and does not imply endorsement.

Likewise, ask through [MAINTAINERS.md](MAINTAINERS.md) before using the
upstream name or logo for a derivative product name, commercial advertising,
or merchandise. This is an origin-clarity request for this project's branding,
not a claim against unrelated products that independently use a similar name.

## Rebranding a fork

The complete mechanical path is documented in
[CONTRIBUTING.md](CONTRIBUTING.md#customizing-for-forks):

1. run `scripts/rebrand_ui.sh "Your App Name"` for both localized UI files;
2. configure the complete fork identity, including `APP_DISPLAY_NAME`,
   application ID, links, public Nostr namespaces, maintainer, and updater;
3. replace the launcher, monochrome, splash-composition, documentation, and
   store assets using [logos/README.md](logos/README.md);
4. run `scripts/check_rebrand_assets.sh <upstream-ref>` and a release build.

Gradle rejects a partial identity and, for fork builds, rejects localized UI
resources that still contain the upstream display name. Protocol/schema
identifiers retained solely for backward compatibility are not presented as
the fork's user-facing identity.

## Questions and corrections

Use the public issue tracker for non-sensitive questions, or the verified Nostr
route in [MAINTAINERS.md](MAINTAINERS.md). If this document identifies a
same-name product inaccurately, please request a factual correction; no legal
claim is required to do so.
