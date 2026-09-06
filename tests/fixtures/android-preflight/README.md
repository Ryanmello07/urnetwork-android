# Android preflight fixtures

The `captured-*` files retain the decisive lines from physical diagnostic run
`20260905-200413`: one guest CPU, lavapipe/swangle/SwiftShader rendering, and a
focused SystemUI ANR. The `clean-*` files retain the equivalent four-CPU host
renderer/Nexus Launcher proof from the controlled `-gpu host` reproduction.

Malformed, missing, and contradictory evidence are separate fail-closed states
so an unrecognized platform dump cannot be mistaken for a healthy device.
The duplicate and equivalent-syntax windows retain the forms observed on
attached Samsung devices, where dumpsys repeats a field or reports the same
component both directly and inside a `Window{...}` wrapper.
