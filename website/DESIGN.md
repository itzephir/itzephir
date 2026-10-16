# Portfolio baseline

Source: [01 · Baseline in Figma](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=14-3481).

| Screen | Dark | Light |
| --- | --- | --- |
| Welcome desktop | `14:1898` | `14:2009` |
| Projects desktop | `14:2734` | `14:2882` |
| Welcome mobile | `14:3029` | `14:3254` |
| Projects mobile | `14:3123` | `14:3346` |

The approved full logo is the connected `itz` monogram followed by `ephir` in
Quicksand Bold, with optical letter spacing fixed in Figma. Source components
are `22:4698` (light ink, dark surfaces) and `22:4699` (dark ink, light surfaces),
in `Brand / Wordmark`. The [brand sheet](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=22-4701)
records the approved composition. Use the complete `wordmark.svg` exports;
the letters are outlines, so no Quicksand font download is needed at runtime.
The 223.3946 × 80 px component is exported on a 224 × 80 px SVG canvas and
scaled by 0.4 into an 89.36 × 32 px desktop slot, matching Figma.

The short connected `itz` mark retains uniform 2 px corners on the 64 px master.
Source variants `22:4601` and `22:4602` remain the SVG favicon sources. Native
16/32 px favicon PNG exports (`22:5040`, `22:5050`, `22:5058`, `22:5068`) are
unchanged. Use the short mark for icons, and the whole-name wordmark in desktop
headers.

Colors and content spacing are defined in `styles.css`; shared shell geometry
is owned by `site-shell.css`. Icons and portrait are local
exports from the same Figma screens. IBM Plex Sans/Mono include Cyrillic and are
self-hosted; font and icon license notices are under `third_party`.

The implementation uses fluid widths and a 720 px breakpoint. The mobile header
contains the webring only; routes are reached through content links and Back.
Within each breakpoint, header dimensions, alignment and control sizes are shared
by all routes and both themes; page-specific header geometry is not allowed.
Only the path beside the wordmark changes: `/`, `/projects` or `/about`, in a
fixed-width slot.
The [site shell contract v1](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=59-2692)
records this architectural boundary with dark/light route examples and mobile
states. The desktop app bar uses component set `59:1689` (masters `59:1687` /
`59:1688`), with only Theme and Path as inputs. Mobile uses set `40:7383`
(masters `40:7381` / `40:7382`). All eight baseline screens use these instances;
the Dev header family `40:7356` composes the same desktop app bar with tabs.
See [ADR 001](architecture/site-shell.md) for code ownership and CI enforcement.
Desktop tabs represent visited pages, preserve their opening order in the
session, and provide separate close controls. See `README.md` for tab behavior.
The profile route adapts the existing biography to the same style because the
baseline includes a profile shortcut but no separate profile screen.

Browser verification captures both baseline pages in both themes and checks
320/390/719/720/768/999/1000/1440 px layouts, identical header geometry across
welcome/projects/about and both themes, loaded assets, logo size, visible named ring
links, 44 px mobile touch targets, theme persistence, and navigation without JS.
Screenshots are generated under `website/build/browser-check` and uploaded as
the `portfolio-screenshots` CI artifact.
