# Third-party notices

This repository distributes Java source only. It does not contain or bundle
native libraries.

The runtime modules use Java's Foreign Function and Memory API to call system
components supplied by the operating system:

- Windows: Component Object Model runtime (`ole32.dll`), Windows Imaging
  Component, Windows Media Foundation (`mfplat.dll`, `mfreadwrite.dll`),
  Direct3D 11 (`d3d11.dll`), and for OpenGL `opengl32.dll`, `user32.dll`,
  `gdi32.dll` and `kernel32.dll`
- macOS: Accelerate.framework (vImage) and OpenGL.framework (CGL)
- Linux: the system EGL and OpenGL libraries (`libEGL.so.1`, and
  `libOpenGL.so.0` or `libGL.so.1`), typically Mesa or a GPU vendor driver

Those system components are not redistributed by this project.

The canonical child POMs retain a test-scope declaration for
[JUnit Jupiter](https://junit.org/junit5/) 5.12.1, licensed under the Eclipse
Public License 2.0. No test sources are included in this snapshot, and the
dependency is not transitive to consumers.
