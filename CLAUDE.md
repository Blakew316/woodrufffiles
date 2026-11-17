# Woodruff Family for Google TV

The family's Google TVs run this app. The LG TV runs its webOS port, in the private repository
`Blakew316/woodrufffiles-webos` (`webos/`).

- The LG TV goes with the group, always. Whatever changes here goes into the webOS port in the same request, and the
  request isn't done until both have their release: a push here makes `v2.0.N`, a push to the port's `main` makes
  `webos-N`. What can't apply to an LG TV is said so in the port's commit, not skipped silently.
- Every push to `claude/busy-heisenberg-vk1mse` builds and releases unless its subject (the first line) has
  `[no release]`, as written; a push to any other branch is built and tested only. `[urgent]` in the subject only names
  the release "rollback", which changes nothing on the TVs today: every TV takes each release at its next check (within
  the hour, or when Woodruff opens) and installs it at the next quiet moment.
- The TVs update themselves with the released app's own updater: change that code only with great care, since a
  broken one stops every TV from updating, even to the fix.
