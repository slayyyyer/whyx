# Releasing to Maven Central

Publishing is local-only. One upload contains three coordinates:

- `io.github.slayyyyer:why-lang`
- `io.github.slayyyyer:why-gradle-plugin`
- `io.github.slayyyyer.why:io.github.slayyyyer.why.gradle.plugin` (the plugin marker)

Nmcp aggregates them, so the runtime, the plugin, the marker, sources, Javadoc and their signatures
are validated together. The version is `whyVersion` in `gradle.properties`; the plugin reads it from
the `why-version.properties` resource, so bump it in that one place before a release.

## One-time setup

1. Sign in to [central.sonatype.com](https://central.sonatype.com/) and verify the
   `io.github.slayyyyer` namespace against the `slayyyyer/why-lang` GitHub repository.
2. Create a GPG key and publish its public key to a supported keyserver. A passphrase is optional;
   the publisher exports the secret key from your local keyring, so an unprotected key needs no
   `SIGNING_PASSWORD`.
3. Create a Central Portal publishing token. Its username and password are not your Maven account
   credentials.

Keep the key and the token out of the repository. `publish-central.sh` prompts for them and writes
neither to disk.

## Releasing

```bash
./gradlew :why-gradle-plugin:test
./gradlew build
./publish-central.sh --dry-run   # optional: build and sign, upload nothing
./publish-central.sh
```

The script prompts for a GPG key ID (exported from your keyring) or the path to an exported private
key, then for the token credentials.

The upload is `USER_MANAGED`, so nothing is public yet. Release the deployment by the id the portal
shows you:

```bash
./gradlew --no-daemon nmcpPublishDeployment -PnmcpDeploymentId=<id>
```

Then wait for propagation. `repo1.maven.org` serves the new coordinates a few minutes after the
release; the deployment status endpoint can lag or answer `500` while it settles, so check the
artifacts themselves:

```bash
curl -fI https://repo1.maven.org/maven2/io/github/slayyyyer/why-lang/0.2.0/why-lang-0.2.0.pom
```

## Publishing the plugin to the Gradle Plugin Portal

The plugin also publishes to the Portal, which is what lets `plugins { id("io.github.slayyyyer.why") }`
resolve without a `pluginManagement` block. The build carries `com.gradle.plugin-publish`; the
credentials are a Portal token, not the Central one.

Store the token in your user-level Gradle properties, not in the repository:

```properties
# ~/.gradle/gradle.properties
gradle.publish.key=...
gradle.publish.secret=...
```

The plugin declaration must include at least one tag. The build sets `tags` in
`why-gradle-plugin/build.gradle.kts`; without it, `publishPlugins` fails with
`Plugin 'why' has no 'tags' property set`.

Run the publication after the Central artifacts are live, because the marker POM points at
`io.github.slayyyyer:why-gradle-plugin` on Maven Central:

```bash
./gradlew --no-daemon publishPlugins
```

Then verify the portal lists the new version:

```bash
curl -fsSL https://plugins.gradle.org/plugin/io.github.slayyyyer.why | grep 'Version'
```

## Skipping the prompts

Every prompt reads an environment variable first:

```bash
export MAVEN_CENTRAL_USERNAME='token username'
export MAVEN_CENTRAL_PASSWORD='token password'
export SIGNING_KEY_ID='your key ID'           # or SIGNING_KEY_FILE=~/why-signing-key.asc
export SIGNING_PASSWORD='passphrase'          # only if the key has one
./publish-central.sh
```
