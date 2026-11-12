# Woodruff Family for Google TV

The family's Google TVs run this app. The LG TV runs its webOS port, in the private repository
`Blakew316/woodrufffiles-webos` (`webos/`).

- The LG TV goes with the group, always. Whatever changes here goes into the webOS port in the same request, and the
  request isn't done until both have their release: a push here makes `v2.0.N`, a push to the port's `main` makes
  `webos-N`. What can't apply to an LG TV is said so in the port's commit, not skipped silently.
- Every push here builds and releases unless its message has `[no release]`; `[urgent]` makes the TVs install at once.
- The TVs update themselves with the released app's own updater: change that code only with great care, since a
  broken one stops every TV from updating, even to the fix.
