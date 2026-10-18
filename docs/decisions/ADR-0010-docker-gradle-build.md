# ADR-0010: Docker-based Gradle is the only supported build path

- **Status:** Accepted (2026-09-10)
- **Deciders:** Comanda founding work
- **Relates to:** design v0.2 §10 (reproducible builds), §15 (green gate in
  Docker), ADR-0009 (F-Droid reproducibility). Borrowed from quire's
  `scripts/dgradle`.

## Context

Android builds fail on the wrong machine for boring reasons: a host JDK one major
version off, a missing SDK platform, an unaccepted licence, a build-tools
revision that differs from the one CI used. Each produces a confusing error a
long way from its cause, and each costs a contributor an evening before they have
written a line of code.

quire solved this by shipping `scripts/dgradle`, a wrapper that runs every Gradle
invocation inside the project's own Docker image. Comanda needs it for the same
reason and for one more: F-Droid checks that a build is reproducible (ADR-0009),
and reproducibility is far easier to argue when the toolchain is a pinned
Dockerfile in the repository rather than a paragraph in a README that everyone
has drifted from.

## Decision

**`scripts/dgradle` is the only supported way to build and test Comanda.**

1. The repository-root `Dockerfile` pins the whole toolchain: JDK
   (`eclipse-temurin:21-jdk-jammy`), the Android command-line tools revision, the
   platform, and the build-tools revision. `dgradle` builds the image on first
   use and then runs `./gradlew` inside it.
2. The image is built and run as `linux/amd64`, so an Apple Silicon laptop and an
   x86 CI runner compile the same bytes. Local builds pay Rosetta emulation for
   that.
3. Gradle and Android caches live in named Docker volumes, so a second build is
   not a second download.
4. **CI runs the same script.** The green gate lives inside the image, so CI and
   a laptop agree by construction rather than by coincidence.
5. Contributors need Docker and nothing else. No host JDK, no Android SDK, no
   licence acceptance. `CONTRIBUTING` says so, and says not to reach for a host
   `gradle` or `./gradlew`.
6. **Corollary for automated contributors.** An agent working on one module must
   not invoke a host `gradle`, `./gradlew`, `adb` or `docker` itself. The build
   is a shared resource, driven centrally, and a stray parallel invocation
   corrupts a cache that several workers share.

## Consequences

- ✅ One build environment, versioned with the code, changing only when a commit
  changes it. Onboarding is `git clone` then `scripts/dgradle test`.
- ✅ F-Droid reproducibility becomes tractable, because the toolchain versions are
  a file rather than a recollection.
- ⚠️ The first run builds the image, roughly five minutes, longer under Rosetta.
  The script says so before it starts, which is the difference between a wait and
  a hang.
- ⚠️ Docker is a hard prerequisite. A contributor who cannot run Docker cannot
  build the project. That is a real exclusion, and it is the price of the
  guarantee.
- ⚠️ amd64 emulation on Apple Silicon is slower than a native arm64 image. That is
  one argument in one script to revisit if it becomes painful.
- ⚠️ Devices and emulators live outside the container. Installing an APK with
  `adb` is a host operation, and instrumented tests are not part of the
  containerized gate. That is survivable only because the interesting logic lives
  in framework-free modules with JVM tests, which §10 already requires.

## Alternatives considered

- **Host `gradlew` with a documented JDK and SDK version.** Rejected:
  documentation is not enforcement. It is exactly the arrangement that produces
  "works on my machine" for everyone whose machine is not the author's.
- **A devcontainer and nothing else.** Rejected: it ties the guarantee to an
  editor, and CI would still need a second path. `dgradle` works in any shell,
  including a CI runner and an agent's non-interactive one.
- **Toolchain pinning with nix or asdf.** Rejected: it pins the JDK and the tools
  but not the OS-level pieces the Android SDK reaches for, and it asks every
  contributor to install a tool most of them do not have, in exchange for a
  weaker guarantee than a container gives.
