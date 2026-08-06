# Job 10 report — the FUSE spike (gates M4)

## Summary

**Verdict: CI-viable.** lazyfs (commit
`045a0b3a1126725e693934e29d3ba15e08cc39ec`, 2026-05-07) builds, mounts
through `/dev/fuse`, and injects un-fsynced-data loss inside privileged
ubuntu:24.04 containers both locally (the unmodified `env/` image) and
on a GitHub-hosted runner — the throwaway `fuse-spike` workflow run
linked below is green with the loss assertion as its exit condition. M4
does **not** need a self-hosted or VM runner. The SUT-shaped rehearsal
produced its own finding: a single-node `ratis-kv` with `--storage` on
lazyfs **cleanly recovers every acknowledged write** after a
clear-cache-plus-`kill -9` power loss, in both fault orderings — Ratis
fsyncs appends before acking, so the plain drop-everything fault
cannot create acked-write loss on one node; M4's leverage is therefore
lazyfs's torn-write faults and quorum-wide drops, and the recommended
M4 shape below is designed around that.

## What was built

| File | One line |
|---|---|
| `.github/workflows/fuse-spike.yml` | the throwaway dispatch-only spike workflow (marked as such): lazyfs at the pinned commit in a privileged container, loss assertion = green |
| `jobs/10-fuse-spike/10_report.md` | this report |

Nothing else — all experiments ran in ad-hoc containers and scratch
space, per the brief.

## Deliverable 1 — local proof (command transcript)

Environment: the unmodified `ratis-jepsen/env:latest` image, ad-hoc
container, host x86_64, kernel 6.18.5. Every command as run
(`$SCRATCH` = the session scratch dir with a host clone of lazyfs):

```
$ docker run -d --privileged --device /dev/fuse --network host \
    -e HTTPS_PROXY=… --name fuse-spike \
    -v $SCRATCH/lazyfs:/lazyfs-src ratis-jepsen/env:latest sleep infinity
$ docker exec fuse-spike bash -c "apt-get update -qq && \
    apt-get install -y -qq g++ cmake libfuse3-dev libfuse3-3 fuse3 git"
$ docker exec fuse-spike bash -c "cp -r /lazyfs-src /root/lazyfs"

# sandbox accommodation (not needed on GHA): the egress proxy 403s
# GitHub archive tarballs, so spdlog (fetched as a tarball by
# libpcache's CMake) is pre-cloned and FetchContent pointed at it;
# lazyfs's own CMake hardcodes libpcache's _deps path, hence the symlink
$ docker exec fuse-spike bash -c "git clone --depth 1 --branch v1.10.0 \
    https://github.com/gabime/spdlog.git /root/spdlog && \
    cd /root/lazyfs/libs/libpcache && mkdir -p build && cd build && \
    cmake -DFETCHCONTENT_SOURCE_DIR_SPDLOG=/root/spdlog .. && \
    cmake --build . -j4"                                   # exit 0
$ docker exec fuse-spike bash -c "ln -sfn /root/spdlog \
    /root/lazyfs/libs/libpcache/build/_deps/spdlog-src && \
    cd /root/lazyfs/lazyfs && ./build.sh"                  # exit 0

$ docker exec fuse-spike bash -c '
  echo user_allow_other >> /etc/fuse.conf
  mkdir -p /tmp/lazyfs.mnt /tmp/lazyfs.root
  cd /root/lazyfs/lazyfs
  ./scripts/mount-lazyfs.sh -c config/default.toml \
      -m /tmp/lazyfs.mnt -r /tmp/lazyfs.root'
# mount.log: "[lazyfs.fifo]: running LazyFS..." after a ~8 s 1 GiB
# cache pre-allocation; then:
$ docker exec fuse-spike mount | grep fuse.lazyfs
lazyfs on /tmp/lazyfs.mnt type fuse.lazyfs (rw,nosuid,nodev,relatime,user_id=0,group_id=0,allow_other)
```

The loss demonstration — one write left un-fsynced, one fsynced
(`dd conv=fsync`):

```
$ echo "unsynced data written at 17:48:36" > /tmp/lazyfs.mnt/unsynced.txt
$ dd if=… of=/tmp/lazyfs.mnt/synced.txt conv=fsync

before drop, through the mount:      both files read back correctly
before drop, in the BACKING root:    synced.txt 33 bytes,
                                     unsynced.txt 0 bytes   <- cached only

$ echo "lazyfs::clear-cache" > /tmp/faults.fifo

after drop, through the mount:
  unsynced.txt: []                            size=0     <- DATA LOST
  synced.txt:   [fsynced data written at 17:48:36] size=33  <- SURVIVED
```

Exactly the power-loss semantics M4 needs: fsynced data durable,
everything else droppable on command.

## Deliverable 2 — GHA proof

`.github/workflows/fuse-spike.yml` (dispatch-only, throwaway-marked)
repeats the experiment on `ubuntu-latest`: privileged `ubuntu:24.04`
container with `--device /dev/fuse`, lazyfs built at the pinned commit
(no proxy accommodations needed — runners fetch the spdlog tarball
directly), mount, un-fsynced + fsynced writes, `lazyfs::clear-cache`,
and hard assertions (`un-fsynced size == 0`, fsynced content intact) so
that green **is** the proof.

TBD-GHA-RUN-LINK

## Deliverable 3 — the SUT-shaped rehearsal

Single-node `ratis-kv` (the Job 09 tarball) with
`--storage /tmp/lazyfs.mnt/ratis-kv`, in the same container:

```
$ /opt/ratis-kv/bin/ratis-kv --id n1 --peers n1=127.0.0.1:6000 \
    --storage /tmp/lazyfs.mnt/ratis-kv &
… ratis-kv server started: id=n1 … storage=/tmp/lazyfs.mnt/ratis-kv …
$ jshell (RaftClient against 127.0.0.1:6000):
PUT k1 1 -> OK   … PUT k5 5 -> OK      GET k3 -> VAL 3
```

Storage before the fault (mount vs backing root identical —
`log_inprogress_0` 4 Mi