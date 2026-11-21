"""Rebuild the bundled client with NDK 27.1.12297006 and an ESnet release archive.

curl -fLO https://downloads.es.net/pub/iperf/iperf-3.22.1.tar.gz
python3 tools/build_iperf3.py /path/to/android-ndk iperf-3.22.1.tar.gz
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

VERSION = "3.22.1"
SHA256 = "de018b5252e2a7068c89fac8b35a295d83334f2b7814aa9532a2a8107b04a4a9"
TARGETS = {
    "arm64-v8a": "aarch64-linux-android",
    "armeabi-v7a": "armv7a-linux-androideabi",
    "x86_64": "x86_64-linux-android",
    "x86": "i686-linux-android",
}
MAIN = Path(__file__).resolve().parents[1] / "app/src/main"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ndk", type=Path)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    if hashlib.sha256(args.archive.read_bytes()).hexdigest() != SHA256:
        parser.error("Archive does not match the published ESnet SHA256")
    clang, = args.ndk.resolve().glob("toolchains/llvm/prebuilt/*/bin/clang")
    tools = clang.parent
    with tempfile.TemporaryDirectory() as work:
        subprocess.run(["tar", "-xzf", args.archive.resolve(), "-C", work], check=True)
        source = Path(work) / f"iperf-{VERSION}"
        for abi, triple in TARGETS.items():
            build = Path(work) / abi
            build.mkdir()
            env = dict(os.environ, CC=str(tools / f"{triple}30-clang"),
                       AR=str(tools / "llvm-ar"), RANLIB=str(tools / "llvm-ranlib"),
                       CFLAGS="-O2 -fPIE", LDFLAGS="-pie -Wl,-z,max-page-size=16384")
            subprocess.run([source / "configure", f"--host={triple}", "--disable-shared",
                            "--enable-static", "--without-openssl", "--without-sctp"],
                           cwd=build, env=env, check=True)
            startup = Path(__file__).resolve().with_name("iperf3_android.c")
            subprocess.run([env["CC"], "-O2", "-fPIE", "-Isrc", "-I" + str(source / "src"), "-c", startup, "-o", "src/android_process.o"],
                           cwd=build, check=True)
            subprocess.run(["make", "-C", "src", "-j4", "iperf3", "iperf3_LDADD=android_process.o libiperf.la",
                            "iperf3_LDFLAGS=-Wl,--wrap=iperf_parse_arguments"],
                           cwd=build, env=env, check=True)
            destination = MAIN / "jniLibs" / abi / "libiperf3_exec.so"
            destination.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run([tools / "llvm-strip", "-o", destination, build / "src/iperf3"], check=True)
        notice = MAIN / "resources/META-INF/iperf3/LICENSE"
        notice.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / "LICENSE", notice)


if __name__ == "__main__":
    main()
