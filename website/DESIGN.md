# Portfolio baseline

Source: [01 · Baseline in Figma](https://www.figma.com/design/zHX90tHJtN7q1PqavIO68s/?node-id=14-3481).

| Screen | Dark | Light |
| --- | --- | --- |
| Welcome desktop | `14:1898` | `14:2009` |
| Projects desktop | `14:2734` | `14:2882` |
| Welcome mobile | `14:3029` | `14:3254` |
| Projects mobile | `14:3123` | `14:3346` |

The approved brand is option 06: Full connection with uniform 2 px corners on
the 64 px master. Source variants are `22:4601` (light ink, dark surfaces) and
`22:4602` (dark ink, light surfaces). Exported SVGs remain unchanged; the header
scales the 64 px artwork into a 28 px slot. Favicon PNGs are native exports from
the 16/32 px instances (`22:5040`, `22:5050`, `22:5058`, `22:5068`).

Colors and spacing are defined in `styles.css`. Icons and portrait are local
exports from the same Figma screens. IBM Plex Sans/Mono include Cyrillic and are
self-hosted; font and icon license notices are under `third_party`.

The implementation uses fluid widths and a 720 px breakpoint. The mobile header
contains the webring only; routes are reached through content links and Back.
Desktop tabs represent visited pages, preserve their opening order in the
session, and provide separate close controls. See `README.md` for tab behavior.
The profile route adapts the existing biography to the same style because the
baseline includes a profile shortcut but no separate profile screen.

Browser verification captures both baseline pages in both themes and checks
320/390/719/720/768/1440 px layouts, loaded assets, logo size, visible named ring
links, 44 px mobile touch targets, theme persistence, and navigation without JS.
Screenshots are generated under `website/build/browser-check` and uploaded as
the `portfolio-screenshots` CI artifact.
