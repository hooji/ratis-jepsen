# Copyright 2026 the ratis-jepsen authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# One image for every role (control and n1..n7), per DESIGN 3. Multi-arch by
# construction: ubuntu:24.04 is a multi-arch manifest, all packages come from
# apt (which resolves per-architecture), and the Clojure CLI is pure-JVM
# installed by the official arch-neutral installer — no arch-pinned URLs.
# The one arch-bound piece, lazyfs, is built in a throwaway stage on amd64
# only (PLAN Q8: lazyfs is x86-CI-only) — other arches get an image without
# the binary, and a --durability run fails loudly at mount proof.

# --- lazyfs build stage (Job 11 / M4) --------------------------------------
# Builds lazyfs at the Job 10 spike's pinned commit (the project calls its
# main branch "probably unstable"; the pin is load-bearing). spdlog is
# pre-cloned and fed to libpcache's CMake FetchContent instead of letting it
# fetch a GitHub archive tarball — the spike's dependency accommodation:
# TLS-inspecting proxies commonly 403 archive tarballs while plain git
# clone works, and the pre-fetch behaves identically on open networks.
# lazyfs's own CMakeLists hardcodes libpcache's _deps path for spdlog
# headers, hence the symlink. Everything here is discarded except /out.
FROM ubuntu:24.04 AS lazyfs-build

ARG DEBIAN_FRONTEND=noninteractive
ARG LAZYFS_COMMIT=045a0b3a1126725e693934e29d3ba15e08cc39ec
ARG SPDLOG_TAG=v1.10.0

# Same optional proxy-CA knob as the main stage (git clone over https needs
# it behind TLS-inspecting proxies); no JVM here, so a single multi-cert
# file into the system store suffices.
ARG EXTRA_CA_B64=
RUN if [ -n "$EXTRA_CA_B64" ]; then \
      mkdir -p /usr/local/share/ca-certificates \
      && echo "$EXTRA_CA_B64" | base64 -d \
           > /usr/local/share/ca-certificates/ratis-jepsen-build-ca.crt; \
    fi

ARG TARGETARCH
RUN set -eu; \
    arch="${TARGETARCH:-$(dpkg --print-architecture)}"; \
    if [ "$arch" = "amd64" ]; then \
      apt-get update && apt-get install -y --no-install-recommends \
        g++ cmake make git ca-certificates pkg-config libfuse3-dev fuse3 \
      && rm -rf /var/lib/apt/lists/* \
      && update-ca-certificates \
      && git clone --depth 1 --branch "${SPDLOG_TAG}" \
           https://github.com/gabime/spdlog.git /opt/spdlog-src \
      && git clone https://github.com/dsrhaslab/lazyfs.git /opt/lazyfs-src \
      && git -C /opt/lazyfs-src checkout "${LAZYFS_COMMIT}" \
      && mkdir -p /opt/lazyfs-src/libs/libpcache/build \
      && cd /opt/lazyfs-src/libs/libpcache/build \
      && cmake -DFETCHCONTENT_SOURCE_DIR_SPDLOG=/opt/spdlog-src .. \
      && cmake --build . -j"$(nproc)" \
      && ln -sfn /opt/spdlog-src \
           /opt/lazyfs-src/libs/libpcache/build/_deps/spdlog-src \
      && cd /opt/lazyfs-src/lazyfs && ./build.sh \
      && mkdir -p /out \
      && cp /opt/lazyfs-src/lazyfs/build/lazyfs /out/lazyfs \
      && cp -a /opt/lazyfs-src/libs/libpcache/build/libpcache.so* /out/ \
      && echo "${LAZYFS_COMMIT}" > /out/COMMIT; \
    else \
      echo "lazyfs: skipping build on ${arch} (x86-CI-only, PLAN Q8)"; \
      mkdir -p /out; \
    fi

# --- the one runtime image -------------------------------------------------
FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# openjdk-21: runs the SUT on nodes and Maven/Clojure on control.
# openssh: control SSHes to nodes as root (Jepsen convention).
# iproute2/iptables/psmisc/procps: fault injection + inspection (partitions,
# SIGSTOP/kill, ss). rsync/scp: artifact distribution. rlwrap: `clj` REPL.
# libfuse3-3/fuse3: runtime for the lazyfs durability mounts (Job 11/M4) —
# fusermount3 and the FUSE libs; inert unless a run passes --durability.
# git: real git on control (Job 17) — jepsen shells it for provenance
# logging and tools.gitlibs needs it should a git dep ever return; the
# always-fails shim run.sh test used to install remains as a guarded
# no-op fallback for images that predate this line.
# gnuplot-nox: jepsen's post-run latency/rate plots (Job 17) — CI stores
# previously carried none because the binary was missing.
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jdk-headless \
      openssh-server \
      openssh-client \
      iproute2 \
      iptables \
      psmisc \
      procps \
      rsync \
      curl \
      ca-certificates \
      rlwrap \
      git \
      gnuplot-nox \
      libfuse3-3 \
      fuse3 \
    && rm -rf /var/lib/apt/lists/*

# lazyfs (Job 11/M4): the binary and its libpcache shared lib from the
# build stage (spdlog links statically; libpcache does not), plus the FUSE
# allow_other permission its mounts use. /opt/lazyfs is empty on non-amd64
# (PLAN Q8) — the harness proves the mount per node and fails a durability
# run loudly when the binary is missing. Inert for every non-durability run.
COPY --from=lazyfs-build /out/ /opt/lazyfs/
RUN echo /opt/lazyfs > /etc/ld.so.conf.d/lazyfs.conf \
    && ldconfig \
    && echo user_allow_other >> /etc/fuse.conf

# Optional extra CA certificate(s) (base64-encoded PEM bundle), for hosts
# that sit behind a TLS-inspecting proxy: trusted system-wide, including the
# JVM keystore, so curl/Maven/Clojure work at build time and runtime. The
# bundle is split one-cert-per-file before update-ca-certificates because
# Debian's ca-certificates-java hook imports only the FIRST cert of each
# file into the JVM keystore — a multi-cert file would be half-trusted
# (system store yes, JVM no; Review 02 finding 2). The split keeps only
# BEGIN/END CERTIFICATE blocks, so comment lines between certs are fine.
# Fails the build loudly if the bundle decodes to no certificate at all.
# A no-op when the build-arg is unset. See env/README.md; run.sh wires
# RJ_EXTRA_CA_BUNDLE to this (with a size pre-flight).
ARG EXTRA_CA_B64=
RUN if [ -n "$EXTRA_CA_B64" ]; then \
      echo "$EXTRA_CA_B64" | base64 -d > /tmp/extra-ca-bundle.pem \
      && awk '/-----BEGIN CERTIFICATE-----/ { n++; keep = 1; f = sprintf("/usr/local/share/ca-certificates/ratis-jepsen-extra-ca-%02d.crt", n) } keep { print >> f } /-----END CERTIFICATE-----/ { keep = 0; close(f) }' \
             /tmp/extra-ca-bundle.pem \
      && rm /tmp/extra-ca-bundle.pem \
      && count=$(ls /usr/local/share/ca-certificates/ratis-jepsen-extra-ca-*.crt | wc -l) \
      && echo "EXTRA_CA_B64: split into ${count} certificate file(s)" \
      && update-ca-certificates; \
    fi

# Clojure CLI tools for the harness on control (Job 04); installed on every
# node only because there is a single image. Pure-JVM, arch-neutral.
ARG CLOJURE_CLI_VERSION=1.12.1.1550
RUN curl -fsSL \
      "https://download.clojure.org/install/linux-install-${CLOJURE_CLI_VERSION}.sh" \
      -o /tmp/clojure-install.sh \
    && bash /tmp/clojure-install.sh \
    && rm /tmp/clojure-install.sh \
    && clojure --version

# sshd: root may log in with a key only (the compose network is private and
# throwaway; keys are generated per-checkout by run.sh, never committed).
# StrictModes off because authorized_keys is a read-only bind mount owned by
# whatever uid owns the checkout on the host — root locally, but e.g. 1001
# on GitHub-hosted runners, where sshd's ownership check would refuse the
# key ("bad ownership or modes") and every node would sit unreachable until
# the ssh-ready timeout. The check defends multi-user machines; inside this
# single-purpose container it defends nothing.
RUN mkdir -p /root/.ssh /run/sshd \
    && chmod 700 /root/.ssh \
    && printf '%s\n' \
         'PermitRootLogin prohibit-password' \
         'UseDNS no' \
         'StrictModes no' \
         > /etc/ssh/sshd_config.d/90-ratis-jepsen.conf

# Client-side ssh config for control: no host-key prompts inside the
# throwaway compose network.
COPY ssh_config /root/.ssh/config
RUN chmod 600 /root/.ssh/config

EXPOSE 22

# /run may be a fresh tmpfs at runtime; recreate sshd's privsep dir then exec.
CMD ["/bin/bash", "-c", "mkdir -p /run/sshd && exec /usr/sbin/sshd -D -e"]
