"""Sahte Cast alıcısı: Cast V2 TLS kanalı + OFFER/ANSWER + şifreli Cast RTP çözümü + RTCP geri bildirimi.
Chromium kaynağındaki biçimlere göre BAĞIMSIZ yeniden yazıldı (göndericiyle ortak kod yok)."""
import json, socket, ssl, struct, subprocess, sys, threading, time, os
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

HERE = os.path.dirname(os.path.abspath(__file__))
TLS_PORT = 18009
errors = []
def check(cond, msg):
    if not cond:
        errors.append(msg); print("FAIL:", msg)

# --- protobuf ---
def varint(n):
    out = b""
    while n >= 0x80:
        out += bytes([(n & 0x7F) | 0x80]); n >>= 7
    return out + bytes([n])
def field_str(tag, s):
    b = s.encode(); return bytes([tag]) + varint(len(b)) + b
def enc_msg(src, dst, ns, payload):
    return b"\x08\x00" + field_str(0x12, src) + field_str(0x1A, dst) + field_str(0x22, ns) + b"\x28\x00" + field_str(0x32, payload)
def dec_msg(b):
    i = 0; f = {}
    def rv():
        nonlocal i
        v = 0; sh = 0
        while True:
            c = b[i]; i += 1
            v |= (c & 0x7F) << sh
            if not c & 0x80: return v
            sh += 7
    while i < len(b):
        k = rv(); fld, wt = k >> 3, k & 7
        if wt == 0: f[fld] = rv()
        elif wt == 2:
            n = rv(); f[fld] = b[i:i+n]; i += n
        else: raise ValueError("wire type")
    return {"src": f[2].decode(), "dst": f[3].decode(), "ns": f[4].decode(), "payload": f[6].decode(), "ptype": f.get(5), "ver": f.get(1)}

def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        c = s.recv(n - len(buf))
        if not c: raise EOFError
        buf += c
    return buf
def send_msg(s, src, dst, ns, obj):
    body = enc_msg(src, dst, ns, json.dumps(obj))
    s.sendall(struct.pack(">I", len(body)) + body)

state = {"offer": None, "udp": None, "recv_ssrc": 7777, "frames": {}, "done": threading.Event(), "sender_addr": None,
         "sr": 0, "pli_sent": False, "nack_sent": False, "dups": 0, "pkts": {}, "stop": False, "launched": False}
TRANSPORT = "web-5"

def aes_decrypt(key, ivmask, frame_id, data):
    nonce = bytearray(16)
    nonce[8:12] = struct.pack(">I", frame_id & 0xFFFFFFFF)
    nonce = bytes(a ^ b for a, b in zip(nonce, ivmask))
    c = Cipher(algorithms.AES(key), modes.CTR(nonce)).decryptor()
    return c.update(data) + c.finalize()

def handle_conn(s):
    try:
        while True:
            n = struct.unpack(">I", recv_exact(s, 4))[0]
            m = dec_msg(recv_exact(s, n))
            check(m["ver"] == 0 and m["ptype"] == 0, "protobuf sürüm/tür alanı")
            j = json.loads(m["payload"]); ns = m["ns"]; t = j.get("type")
            if ns.endswith("tp.heartbeat"):
                if t == "PING": send_msg(s, "receiver-0", m["src"], ns, {"type": "PONG"})
            elif ns.endswith("tp.connection"):
                print("  alıcı: CONNECT ->", m["dst"])
            elif ns.endswith("cast.receiver") and t == "LAUNCH":
                check(j.get("appId") == "0F5096E8", "appId")
                state["launched"] = True
                send_msg(s, "receiver-0", m["src"], ns, {"type": "RECEIVER_STATUS", "requestId": j["requestId"], "status": {"applications": [
                    {"appId": "0F5096E8", "transportId": TRANSPORT, "sessionId": "sess-1", "namespaces": [{"name": "urn:x-cast:com.google.cast.webrtc"}]}]}})
            elif ns.endswith("cast.receiver") and t == "STOP":
                print("  alıcı: STOP alındı"); state["stop"] = True
            elif ns.endswith("cast.webrtc") and t == "OFFER":
                check(m["dst"] == TRANSPORT, "OFFER hedefi transportId olmalı")
                offer = j["offer"]; state["offer"] = offer
                check(offer["castMode"] == "mirroring", "castMode")
                st = offer["supportedStreams"]; print("  alıcı: OFFER akış sayısı", len(st), json.dumps(st[0])[:300])
                v = [x for x in st if x["type"] == "video_source"][0]
                for k in ["index","codecName","rtpProfile","rtpPayloadType","ssrc","targetDelay","aesKey","aesIvMask","timeBase","maxFrameRate","maxBitRate","resolutions","renderMode"]:
                    check(k in v, "video OFFER alanı yok: " + k)
                check(v["codecName"] == "h264" and v["rtpPayloadType"] == 96 and v["timeBase"] == "1/90000", "video akış değerleri")
                check(len(bytes.fromhex(v["aesKey"])) == 16 and len(bytes.fromhex(v["aesIvMask"])) == 16, "aes uzunlukları")
                state["video"] = v
                u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.bind(("127.0.0.1", 0)); u.settimeout(0.5)
                state["udp"] = u
                threading.Thread(target=udp_loop, daemon=True).start()
                send_msg(s, TRANSPORT, m["src"], ns, {"type": "ANSWER", "seqNum": j["seqNum"], "result": "ok", "answer": {
                    "castMode": "mirroring", "receiverGetStatus": True, "sendIndexes": [v["index"]], "ssrcs": [state["recv_ssrc"]],
                    "udpPort": u.getsockname()[1], "constraints": {"video": {"maxDimensions": {"width": 1280, "height": 720, "frameRate": "30"}}}}})
    except EOFError:
        pass
    except Exception as e:
        errors.append("kontrol kanalı hatası: %r" % e); print("EXC", repr(e))

def udp_loop():
    u = state["udp"]; v = state["video"]
    key = bytes.fromhex(v["aesKey"]); iv = bytes.fromhex(v["aesIvMask"]); ssrc = v["ssrc"]
    pending = {}  # frame_id8 -> {pid: (flags, payload)}
    last_seq = None
    while not state["done"].is_set():
        try:
            d, addr = u.recvfrom(4096)
        except socket.timeout:
            continue
        state["sender_addr"] = addr
        if 194 <= d[1] <= 210:  # RTCP
            check(d[0] == 0x80 and d[1] == 200 and len(d) == 28 and struct.unpack(">H", d[2:4])[0] == 6, "SR biçimi")
            sr_ssrc, ntp_s, ntp_f, rtp, pc, oc = struct.unpack(">IIIIII", d[4:28])
            check(sr_ssrc == ssrc, "SR ssrc")
            check(abs(ntp_s - (time.time() + 2208988800)) < 5, "SR NTP saniyesi")
            state["sr"] += 1
            continue
        check(d[0] == 0x80, "RTP V=2")
        marker = d[1] >> 7; pt = d[1] & 0x7F
        seq, ts, s = struct.unpack(">HII", d[2:12])
        check(pt == 96 and s == ssrc, "payload type / ssrc")
        b0 = d[12]; fid8 = d[13]; pid, maxpid = struct.unpack(">HH", d[14:18]); ref8 = d[18]
        key_f = bool(b0 & 0x80); has_ref = bool(b0 & 0x40); nexts = b0 & 0x3F
        check(has_ref, "referans bayrağı")
        off = 19
        if pid == 0:
            check(nexts == 1, "ilk pakette 1 uzantı olmalı")
        else:
            check(nexts == 0, "ilk paket dışında uzantı olmamalı")
        if nexts:
            check(d[off] == 4 and d[off+1] == 2, "uzantı başlığı")
            delay = struct.unpack(">H", d[off+2:off+4])[0]; check(delay == 250, "playout delay uzantısı")
            off += 4
        payload = d[off:]
        check(marker == (1 if pid == maxpid else 0), "marker yalnızca son pakette")
        key_id = (fid8, pid)
        if key_id in state["pkts"]:
            state["dups"] += 1
            continue  # yeniden gönderilen paket
        state["pkts"][key_id] = True
        fr = pending.setdefault(fid8, {"parts": {}, "key": key_f, "max": maxpid, "ref": ref8})
        fr["parts"][pid] = payload
        check(fr["key"] == key_f and fr["max"] == maxpid, "kare içinde tutarlı başlık")
        if len(fr["parts"]) == maxpid + 1:
            data = b"".join(fr["parts"][i] for i in range(maxpid + 1))
            fid = len(state["frames"])  # kare kimlikleri 0'dan sırayla
            check(fid8 == fid & 0xFF, "kare kimliği sıralı (0'dan)")
            check(ref8 == ((fid if key_f else fid - 1) & 0xFF), "referans kare kimliği")
            state["frames"][fid] = (key_f, aes_decrypt(key, iv, fid, data))
            del pending[fid8]
            n = len(state["frames"])
            # PLI + ACK/NACK testleri
            if n == 6 and not state["pli_sent"]:
                state["pli_sent"] = True
                u.sendto(struct.pack(">BBHII", 0x81, 206, 2, state["recv_ssrc"], ssrc), addr)
            if n == 10 and not state["nack_sent"]:
                state["nack_sent"] = True
                # ACK kare 8; kare 9 paket 0 + (varsa) paket 1 NACK
                loss = bytes([9]) + struct.pack(">H", 0) + bytes([0b00000001])
                body = struct.pack(">IIIBBH", state["recv_ssrc"], ssrc, 0x43415354, 8, 1, 250) + loss
                u.sendto(bytes([0x8F, 206]) + struct.pack(">H", (len(body) + 4) // 4 - 1) + body, addr)
            if n >= 40:
                state["done"].set()

def main():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(os.path.join(HERE, "cert.pem"), os.path.join(HERE, "key.pem"))
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); srv.bind(("127.0.0.1", TLS_PORT)); srv.listen(1)
    sent = os.path.join(HERE, "sent.bin")
    drv = subprocess.Popen(["java", "-cp", os.path.join(HERE, "out.jar") + os.pathsep + os.path.join(HERE, "json.jar"), "DriverKt", str(TLS_PORT), sent],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    out_lines = []
    threading.Thread(target=lambda: [out_lines.append(l.rstrip()) for l in drv.stdout], daemon=True).start()
    srv.settimeout(15)
    conn, _ = srv.accept()
    s = ctx.wrap_socket(conn, server_side=True)
    threading.Thread(target=handle_conn, args=(s,), daemon=True).start()
    drv.wait(timeout=60)
    time.sleep(0.5)
    print("\n".join(l for l in out_lines if l.startswith("STATUS") or l.startswith("DONE") or l.startswith("FAIL") or l.startswith("W/")))
    # gönderilenleri karşılaştır
    frames = []
    with open(sent, "rb") as f:
        while True:
            h = f.read(4)
            if len(h) < 4: break
            n = struct.unpack(">I", h)[0]; k = f.read(1)[0]; frames.append((bool(k), f.read(n)))
    check(len(state["frames"]) == len(frames) == 40, "alınan kare sayısı %d / gönderilen %d" % (len(state["frames"]), len(frames)))
    for i, (k, data) in enumerate(frames):
        if i in state["frames"]:
            check(state["frames"][i] == (k, data), "kare %d içeriği (AES çözümü) tutmuyor" % i)
    check(state["sr"] >= 10, "SR sayısı yetersiz: %d" % state["sr"])
    check(state["dups"] >= 1, "NACK sonrası yeniden gönderim gelmedi")
    check(any("keyframe-request" in l for l in out_lines), "PLI -> anahtar kare isteği tetiklenmedi")
    check(any("STATUS streaming" in l for l in out_lines), "streaming durumu yok")
    check(any("canvas 1280 x 720" in l for l in out_lines), "ANSWER tuval kısıtı okunmadı")
    check(state["stop"], "STOP gönderilmedi")
    print("SR:", state["sr"], "tekrarlanan paket:", state["dups"], "alınan kare:", len(state["frames"]))
    print("SONUÇ:", "BAŞARILI" if not errors else "HATA %d" % len(errors))
    sys.exit(1 if errors else 0)
main()
