"""Cast gönderici mantığını (CastChannel/CastStream/CastBackend) Android olmadan JVM'de derler ve sahte alıcıyla sınar.
Kullanım (kotlinc ve org.json jar'ı gerekir):
    python prepare.py <kotlinc-yolu> <json.jar-yolu>
    python fake_receiver.py
"""
import os, shutil, subprocess, sys
here = os.path.dirname(os.path.abspath(__file__))
repo_src = os.path.normpath(os.path.join(here, "..", "..", "app", "src", "main", "java", "com", "example", "screenmirror"))
kotlinc, json_jar = sys.argv[1], sys.argv[2]
src = os.path.join(here, "build-src")
shutil.rmtree(src, ignore_errors=True)
os.makedirs(os.path.join(src, "android", "util")); os.makedirs(os.path.join(src, "com", "example", "screenmirror", "cast"))
for f in ("CastChannel.kt", "CastStream.kt", "CastBackend.kt"):
    shutil.copy(os.path.join(repo_src, "cast", f), os.path.join(src, "com", "example", "screenmirror", "cast", f))
shutil.copy(os.path.join(repo_src, "FrameSink.kt"), os.path.join(src, "com", "example", "screenmirror", "FrameSink.kt"))
mb = open(os.path.join(repo_src, "MirrorBackend.kt"), encoding="utf-8").read()
open(os.path.join(src, "com", "example", "screenmirror", "MirrorBackend.kt"), "w", encoding="utf-8").write(
    mb[:mb.index("object AirPlayBackendLoader")].replace("import android.content.Context\n", ""))
open(os.path.join(src, "android", "util", "Log.kt"), "w").write(
    "package android.util\nobject Log {\n"
    "    @JvmStatic fun d(tag: String, msg: String): Int { println(\"D/$tag: $msg\"); return 0 }\n"
    "    @JvmStatic fun i(tag: String, msg: String): Int { println(\"I/$tag: $msg\"); return 0 }\n"
    "    @JvmStatic fun w(tag: String, msg: String): Int { println(\"W/$tag: $msg\"); return 0 }\n"
    "    @JvmStatic fun w(tag: String, msg: String, tr: Throwable): Int { println(\"W/$tag: $msg $tr\"); return 0 }\n}\n")
open(os.path.join(src, "com", "example", "screenmirror", "cast", "OpusEncoder.kt"), "w").write(
    "package com.example.screenmirror.cast\nclass OpusEncoder(bitrate: Int, private val onPacket: (ByteArray, Long) -> Unit) {\n"
    "    fun feed(pcm: ByteArray, size: Int) {}\n    fun close() {}\n    companion object { const val SAMPLE_RATE = 48000; fun isAvailable(): Boolean = false }\n}\n")
open(os.path.join(src, "com", "example", "screenmirror", "cast", "CastTarget.kt"), "w").write(
    "package com.example.screenmirror.cast\ndata class CastTarget(val name: String, val ip: String, val port: Int, val model: String, val id: String)\n")
shutil.copy(os.path.join(here, "Driver.kt"), os.path.join(src, "Driver.kt"))
shutil.copy(json_jar, os.path.join(here, "json.jar"))
subprocess.check_call([kotlinc, "-nowarn", "-cp", json_jar, "-include-runtime", "-d", os.path.join(here, "out.jar"), src])
if not os.path.exists(os.path.join(here, "cert.pem")):
    subprocess.check_call(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", os.path.join(here, "key.pem"),
                           "-out", os.path.join(here, "cert.pem"), "-days", "30", "-subj", "/CN=fake-cast"])
print("hazir: python fake_receiver.py")
