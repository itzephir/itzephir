---
name: itzephir
category: Personal workspace
surface: web
colors:
  background: '#1b1d20'
  border: '#383d43'
  text: '#eef0f2'
  accent: '#adbed0'
---

# itzephir

> Category: Personal workspace
> Surface: web

A calm editor and product-app design system with IBM Plex typography, neutral
surfaces, a muted blue accent, and equal dark and light themes. Reuse the approved
tokens, outlined branding, existing CSS components and responsive compositions.

## Start here

- `components.html`: interactive foundations and component specimens.
- `tokens.css`: exact Workspace colors, spacing and radius variables from Figma.
- `design-tokens.json`: machine-readable tokens with original Figma variable IDs.
- `assets/styles.css`, `assets/site-shell.css`, `assets/dev.css`: existing site
  styles and component selectors; compose these instead of inventing replacements.
- `assets/design/{dark,light}/`: original outlined wordmarks and icon exports.
- `sources/portfolio-design.md`: approved screen specifications and Figma IDs.
- `USAGE.md`: the boundary between reusable product foundations and portfolio UI.

## Typography

- **Display:** "IBM Plex Sans" — weights 400, 500; fallbacks: sans-serif
- **Body:** "IBM Plex Sans" — weights 400, 500; fallbacks: sans-serif
- **Mono:** "IBM Plex Mono" — weights 400, 500; fallbacks: monospace

Use semantic names: background `bg`, recessed `side`, card `panel`, hover `raised`,
border `line`, foreground `text`, secondary `muted`, emphasis `accent`, selection
`selected`, button foreground `inverse`, button fill `button`, and warning
`badge-status`. Read both theme values from the token contract. Set `data-theme`
on the root or a `.portfolio` surface. Do not introduce gradients, glow or competing
decorative accents. Status colors must communicate a labelled state.

Use IBM Plex Sans for UI and prose and IBM Plex Mono for paths, code and metadata.
Bundle the supplied font files and license notices. Body UI is 15/22 px; secondary
text 13/20; prose 18/28. Main page headings use 40/48 on desktop and 32/40 on mobile.
The portfolio welcome uses 64/68, then 48/54 on tablets, and 40/44 on mobile with
the existing narrow-container reductions. Quicksand belongs to the outlined
wordmark; it is not the UI font.

## Components

- `.icon-button`: 44 × 44 target, 8 px corners, raised hover and visible focus.
- `.shortcut`: 64 px high navigation card, Plex Mono 14/24, 12 px corners.
- `.project`: panel surface, 24 px padding, 12 px corners, accent hover outline.
- `.profile-panel`: 32 px padding desktop, 24 px mobile, 12 px corners.
- `.dev-ui .button`, `.secondary`: primary and secondary actions, 44 px minimum.
- `.dev-ui .field`: labelled input/select/textarea, 44 px inputs, 112 px textarea.
- `.dev-ui .badge`: labelled neutral/pending/rejected readiness or review state.
- `.dev-ui .notice`: explanatory state, selected surface, 20 px padding.
- `.staging-badge`: environment-only notice; use the original isolated stylesheet.

Use existing icon assets and the complete outlined `wordmark.svg`. Preserve its
aspect ratio and internal spacing. Use the connected short `itz` mark for icons.
Keep the original portrait in its welcome slots, with no desaturation or filters.

## Responsive and accessible behavior

Support both themes together and test at 320, 390, 720 and 1440 px. The primary
breakpoint is 720 px. Use the supplied spacing scale, at least 44 px touch targets,
keyboard focus, plain labels and sensible empty/error/loading states. Welcome
navigation stays a 2 × 2 grid. Portrait and copy are side by side on desktop and
stacked on mobile. Do not shrink desktop navigation into a mobile toolbar.

The portfolio has one shared header/footer owner. Desktop headers are 60 px;
mobile headers 69 px and contain only the webring. Preserve the shared shell's
geometry across routes and themes. Keep theme controls in mobile page content.
The original live application owns server navigation, authentication and forms;
OpenDesign copies are design previews and must not submit to those backends.

## Reuse in other personal products

Reuse the visual foundation, typography, semantic palette, components and
accessibility rules. Adapt composition and navigation to the product. The webring,
visited-page tabs, route names, portfolio header dimensions and Ktor backend are
specific to itzephir. Preserve each project's deliberate design decisions.
