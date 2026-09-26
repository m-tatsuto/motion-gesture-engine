# Package release verification

The repository root is the public Swift Package URL. Android's four modules
use Gradle Maven publications, and `jitpack.yml` builds their artifacts from a
GitHub tag with JDK 21. The same tag is the SwiftPM and JitPack version.

Before creating an immutable release tag:

1. Run `swift test` and `swift test --package-path swift` from the repository root.
2. Run `npm ci && npm test` and `./android/gradlew -p android test`.
3. Run the publication tasks locally with the intended tag version and JitPack
   group, then inspect the generated POM and module metadata, including
   cross-module dependency coordinates.
4. Create and push the tag only after its commit is reviewed and merged.
5. Request each JitPack module from the tag, check the JitPack build status,
   and compile a clean external Android consumer. Resolve the Swift package in
   a clean external iOS consumer. A green source build alone is not proof that
   public packages are retrievable.

Use `com.github.m-tatsuto.motion-gesture-engine` as the JitPack group and the
Git tag as the version. The JitPack configuration passes those values to Gradle
so transitive module POMs point at the same published release. No credentials,
production motion traces, or uploaded app telemetry belong in a package release.

The `0.1.0-beta.1` release exposes the v0.1 measurement foundation and the
immutable legacy baseline. It does not claim a production-ready new detector or
validated device behavior.
