# Reproducible Android build environment for Usuals.
#
# Why this exists: `scripts/dgradle` runs every Gradle invocation inside this
# image, so a contributor needs neither a host JDK nor an Android SDK, and CI
# and a laptop compile the same bytes. Borrowed from quire, where the same
# arrangement is what makes F-Droid reproducible-build checks tractable.
FROM --platform=linux/amd64 node:26.12.1-bookworm-slim@sha256:28147155d47dc31abe0a46e0d12b59b2fb3d0a2dcc5dc65fce76a074180b16fe AS web-tests
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-jammy@sha256:742daacb9316be4b6fc60258f6e4ac2a11882f6c597373c92290a57bfad0d2e0

ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM=android-36
# AGP 8.10 builds with 35.0.0, its minimum. scripts/sign-release and
# scripts/check-apk-reproducibility run apksigner and aapt from 34.0.0.
ARG ANDROID_BUILD_TOOLS=35.0.0
ARG ANDROID_SIGNING_BUILD_TOOLS=34.0.0

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/root/.gradle \
    DEBIAN_FRONTEND=noninteractive

ENV PATH=$PATH:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip git ca-certificates python3 python3-venv \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
 && curl -fsSL -o /tmp/cmdline-tools.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip \
 && unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools \
 && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
 && rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses >/dev/null \
 && sdkmanager --install \
        "platform-tools" \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "build-tools;${ANDROID_SIGNING_BUILD_TOOLS}"

# Browser protocol tests use the same Docker entry point; no npm dependencies.
COPY --from=web-tests /usr/local/bin/node /usr/local/bin/node

WORKDIR /workspace
