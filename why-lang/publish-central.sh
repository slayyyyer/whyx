#!/usr/bin/env bash
# Uploads the Why runtime and Gradle plugin to the Central Portal.
# The deployment is USER_MANAGED: nothing is released until the release command runs.
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: ./publish-central.sh [--dry-run]

Uploads why-lang, why-gradle-plugin and the plugin marker to the Central Portal
as one USER_MANAGED deployment, then prints the command that releases it.

Prompts for anything not already set:

  SIGNING_KEY_ID      GPG key ID, exported from the local keyring
  SIGNING_KEY_FILE    path to an exported ASCII-armored private key
  SIGNING_KEY         the armored key itself (skips export)
  SIGNING_PASSWORD    only for a passphrase-protected key
  MAVEN_CENTRAL_USERNAME / MAVEN_CENTRAL_PASSWORD
                      Central Portal token credentials

Options:
  --dry-run   build and sign locally without uploading anything
  -h, --help  show this message
EOF
}

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"

dry_run=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) dry_run=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; usage >&2; exit 2 ;;
    esac
done

if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -x "$root/.tools/jdk/bin/java" ]]; then
        export JAVA_HOME="$root/.tools/jdk"
        export PATH="$JAVA_HOME/bin:$PATH"
    else
        echo "JAVA_HOME is not set and $root/.tools/jdk was not found." >&2
        exit 1
    fi
fi

# Resolve the signing key: inline armor, a key file, a key ID, or a prompt.
if [[ -z "${SIGNING_KEY:-}" ]]; then
    key_input="${SIGNING_KEY_FILE:-${SIGNING_KEY_ID:-}}"
    if [[ -z "$key_input" ]]; then
        read -r -p "GPG key ID or path to ASCII-armored private key: " key_input
    fi

    if [[ -f "$key_input" ]]; then
        SIGNING_KEY="$(<"$key_input")"
    elif [[ -n "${SIGNING_KEY_FILE:-}" ]]; then
        echo "SIGNING_KEY_FILE does not exist: $key_input" >&2
        exit 1
    else
        if ! command -v gpg >/dev/null 2>&1; then
            echo "gpg is not installed; pass an exported private key file instead." >&2
            exit 1
        fi
        SIGNING_KEY="$(gpg --batch --armor --export-secret-keys "$key_input" || true)"
        if [[ -z "$SIGNING_KEY" ]]; then
            echo "No secret key found for: $key_input" >&2
            exit 1
        fi
    fi
fi

if [[ -z "${MAVEN_CENTRAL_USERNAME:-}" ]]; then
    read -r -p "Central Portal token username: " MAVEN_CENTRAL_USERNAME
fi
if [[ -z "${MAVEN_CENTRAL_PASSWORD:-}" ]]; then
    read -r -s -p "Central Portal token password: " MAVEN_CENTRAL_PASSWORD
    printf '\n'
fi

if [[ -z "$MAVEN_CENTRAL_USERNAME" || -z "$MAVEN_CENTRAL_PASSWORD" ]]; then
    echo "Central Portal token username and password are required." >&2
    exit 1
fi

# An unprotected signing key needs no password.
export SIGNING_KEY
export SIGNING_PASSWORD="${SIGNING_PASSWORD:-}"
export MAVEN_CENTRAL_USERNAME
export MAVEN_CENTRAL_PASSWORD

gradle_args=(--no-daemon publishAggregationToCentralPortal --stacktrace)
if (( dry_run )); then
    gradle_args+=(--dry-run)
fi
./gradlew "${gradle_args[@]}"

cat <<'EOF'

Upload complete. The deployment was validated and is waiting for a release.

Open https://central.sonatype.com/ to copy the deployment id, then release it:

  ./gradlew --no-daemon nmcpPublishDeployment -PnmcpDeploymentId=<id>

Publication to Maven Central then takes a few minutes to propagate.
EOF
