# Third-party notices

This repository distributes Java source only. It does not contain or bundle
native libraries.

The runtime modules use Java's Foreign Function and Memory API to call Windows
system components supplied by the operating system:

- Component Object Model runtime (`ole32.dll`)
- Windows Imaging Component
- Windows Media Foundation (`mfplat.dll`, `mfreadwrite.dll`)
- Direct3D 11 (`d3d11.dll`)

Those system components are not redistributed by this project.

The canonical child POMs retain a test-scope declaration for
[JUnit Jupiter](https://junit.org/junit5/) 5.12.1, licensed under the Eclipse
Public License 2.0. No test sources are included in this snapshot, and the
dependency is not transitive to consumers.
