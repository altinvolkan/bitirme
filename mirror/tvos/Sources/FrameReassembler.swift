import Foundation

/// UDP parçalarını kareye birleştirir. Gecikme için kayıp karelerde beklemez:
/// boşluk görürse `needKeyframe` işaretler, bir sonraki keyframe'e kadar P-kareleri atar.
struct FrameReassembler {
    private struct Partial {
        var parts: [Data?]
        var received = 0
        var key: Bool
    }

    private var pending: [UInt32: Partial] = [:]
    private var lastDelivered: UInt32?
    private(set) var needKeyframe = true

    private func isNewer(_ a: UInt32, than b: UInt32) -> Bool {
        Int32(bitPattern: a &- b) > 0
    }

    mutating func reset() {
        pending.removeAll()
        lastDelivered = nil
        needKeyframe = true
    }

    /// Kare tamamlandıysa (ve çözülebilirse) Annex-B access unit'i döner.
    mutating func push(frameId: UInt32, index: Int, count: Int, key: Bool, payload: Data) -> Data? {
        guard count > 0, index < count else { return nil }
        if let last = lastDelivered, !isNewer(frameId, than: last) { return nil } // eski/yinelenen

        var p = pending[frameId] ?? Partial(parts: Array(repeating: nil, count: count), key: key)
        guard p.parts.count == count else { return nil }
        if p.parts[index] == nil {
            p.parts[index] = payload
            p.received += 1
        }
        if key { p.key = true }

        guard p.received == count else {
            pending[frameId] = p
            if pending.count > 6, let oldest = pending.keys.min(by: { isNewer($1, than: $0) }) {
                pending[oldest] = nil
                needKeyframe = true
            }
            return nil
        }

        // Kare tamam: daha eski bekleyenleri at
        pending[frameId] = nil
        for k in Array(pending.keys) where isNewer(frameId, than: k) { pending[k] = nil }

        let contiguous = lastDelivered.map { frameId == $0 &+ 1 } ?? false
        lastDelivered = frameId

        var frame = Data()
        for part in p.parts { frame.append(part!) }

        if p.key {
            needKeyframe = false
            return frame
        }
        if contiguous && !needKeyframe { return frame }
        needKeyframe = true
        return nil
    }
}
