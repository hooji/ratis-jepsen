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
FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

# openjdk-21: runs the SUT on nodes and Maven/Clojure on control.
# openssh: control SSHes to nodes as root (Jepsen convention).
# iproute2/iptables/psmisc/procps: fault injection + inspection (partitions,
# SIGSTOP/kill, ss). rsync/scp: artifact distribution. rlwrap: `clj` REPL.
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
      fuse3 \
      libfuse3-3 \
    && rm -rf /var/lib/apt/lists/*

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

# lazyfs (M4, Job 11): the FUSE filesystem whose page cache only persists
# what the application fsyncs, so a fault can drop the rest — power-loss
# simulation for the durability nemeses. Pinned to the commit Job 10's
# spike proved out; the spike's two accommodations are baked in:
#
#  1. spdlog is pre-cloned and fed to CMake via FETCHCONTENT_SOURCE_DIR_SPDLOG
#     because libpcache fetches it as a GitHub *archive tarball*, which
#     TLS-inspecting egress proxies commonly 403 (git clone works). Harmless
#     where the proxy does not exist.
#  2. lazyfs's own CMakeLists hardcodes libpcache's _deps path for the spdlog
#     headers, so the clone is symlinked into that path before its build.
#
# Build-only packages are purged in the same layer; the runtime needs only
# fuse3/libfuse3-3 (installed above) plus libpcache.so. INERT BY DEFAULT:
# nothing here runs unless the harness mounts a lazyfs (--durability), so
# every existing scenario behaves exactly as before.
ARG LAZYFS_COMMIT=045a0b3a1126725e693934e29d3ba15e08cc39ec
ARG SPDLOG_TAG=v1.10.0
RUN apt-get update \
    && apt-get install -y --no-install-recommends g++ make cmake git libfuse3-dev \
    && git clone https://github.com/dsrhaslab/lazyfs.git /tmp/lazyfs \
    && git -C /tmp/lazyfs checkout --quiet "${LAZYFS_COMMIT}" \
    && git clone --depth 1 --branch "${SPDLOG_TAG}" \
         https://github.com/gabime/spdlog.git /tmp/spdlog \
    && mkdir -p /tmp/lazyfs/libs/libpcache/build \
    && cd /tmp/lazyfs/libs/libpcache/build \
    && cmake -DFETCHCONTENT_SOURCE_DIR_SPDLOG=/tmp/spdlog .. \
    && cmake --build . -j"$(nproc)" \
    && ln -sfn /tmp/spdlog /tmp/lazyfs/libs/libpcache/build/_deps/spdlog-src \
    && cd /tmp/lazyfs/lazyfs \
    && ./build.sh \
    && mkdir -p /opt/lazyfs/bin \
    && cp /tmp/lazyfs/lazyfs/build/lazyfs /opt/lazyfs/bin/lazyfs \
    && cp /tmp/lazyfs/libs/libpcache/build/libpcache.so /usr/local/lib/ \
    && ldconfig \
    && printf '%s\n' "${LAZYFS_COMMIT}" > /opt/lazyfs/COMMIT \
    && test -x /opt/lazyfs/bin/lazyfs \
    && apt-get purge -y --auto-remove g++ make cmake git libfuse3-dev \
    && rm -rf /tmp/lazyfs /tmp/spdlog /var/lib/apt/lists/*

# FUSE needs allow_other for a mount made by one process to be usable by
# another (the SUT writes through a mount the harness created).
RUN printf 'user_allow_other\n' >> /etc/fuse.conf

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
