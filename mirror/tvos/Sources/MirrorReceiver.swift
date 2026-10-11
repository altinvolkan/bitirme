import Foundation
import Network
import UIKit

/// UDP dinler, Bonjour ile kendini yayınlar, kareleri birleştirip renderer'a verir.
final class MirrorReceiver: ObservableObject {
    @Published var connected = false
    @Published var status = "Başlatılıyor…"
    @Published var stats = ""
    @Published var address = ""

    let renderer = H264Renderer()

    private let queue = DispatchQueue(label: "mirror.rx", qos: .userInteractive)
    private var listener: NWListener?
    private var connection: NWConnection?
    private var reassembler = FrameReassembler()
    private var lastKeyframeRequest = DispatchTime(uptimeNanoseconds: 0)
    private var lastPacket = DispatchTime(uptimeNanoseconds: 0)
    private var frames = 0
    private var bytes = 0
    private var timer: DispatchSourceTimer?

    init() {
        renderer.onNeedKeyframe = { [weak self] in self?.requestKeyframe() }
    }

    func start() {
        let ip = localIPv4() ?? "?"
        address = "\(ip):\(Proto.port)"
        do {
            let params = NWParameters.udp
            let listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: Proto.port)!)
            listener.service = NWListener.Service(
                name: "ScreenMirror-\(UIDevice.current.name)", type: Proto.serviceType)
            listener.stateUpdateHandler = { [weak self] state in
                DispatchQueue.main.async {
                    switch state {
                    case .ready: self?.status = "Android cihazından bağlantı bekleniyor"
                    case .failed(let e): self?.status = "Dinleyici hatası: \(e)"
                    default: break
                    }
                }
            }
            listener.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            status = "Dinleyici açılamadı: \(error)"
        }
        startStatsTimer()
    }

    // MARK: - Bağlantı

    private func accept(_ conn: NWConnection) {
        // Yeni gönderici (yeni UDP kaynak portu) => eskisini bırak
        connection?.cancel()
        connection = conn
        reassembler.reset()
        conn.start(queue: queue)
        receive(on: conn)
        requestKeyframe(force: true)
    }

    private func receive(on conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, error in
            guard let self, conn === self.connection else { return }
            if let data, !data.isEmpty { self.handle(data) }
            if error == nil { self.receive(on: conn) }
        }
    }

    private func handle(_ d: Data) {
        guard d.count > Proto.headerSize, d[d.startIndex] == Proto.typeVideo else { return }
        let s = d.startIndex
        let key = d[s + 1] & Proto.flagKeyframe != 0
        let frameId = (UInt32(d[s + 2]) << 24) | (UInt32(d[s + 3]) << 16) | (UInt32(d[s + 4]) << 8) | UInt32(d[s + 5])
        let idx = (Int(d[s + 6]) << 8) | Int(d[s + 7])
        let count = (Int(d[s + 8]) << 8) | Int(d[s + 9])

        lastPacket = .now()
        bytes += d.count

        if let frame = reassembler.push(
            frameId: frameId, index: idx, count: count, key: key,
            payload: d.subdata(in: (s + Proto.headerSize)..<d.endIndex)) {
            frames += 1
            renderer.render(accessUnit: frame)
        }
        if reassembler.needKeyframe { requestKeyframe() }
    }

    private func requestKeyframe(force: Bool = false) {
        let now = DispatchTime.now()
        if !force, now.uptimeNanoseconds - lastKeyframeRequest.uptimeNanoseconds < 200_000_000 { return }
        lastKeyframeRequest = now
        connection?.send(content: Data([Proto.typeKeyframeRequest]), completion: .idempotent)
    }

    // MARK: - İstatistik / bağlantı durumu

    private func startStatsTimer() {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in
            guard let self else { return }
            let idleSeconds = Double(DispatchTime.now().uptimeNanoseconds - self.lastPacket.uptimeNanoseconds) / 1e9
            let isConnected = idleSeconds < 3 && self.lastPacket.uptimeNanoseconds > 0
            let text = "\(self.frames) fps · \(self.bytes * 8 / 1000) kbps"
            self.frames = 0
            self.bytes = 0
            DispatchQueue.main.async {
                self.connected = isConnected
                self.stats = isConnected ? text : ""
            }
        }
        t.resume()
        timer = t
    }
}
