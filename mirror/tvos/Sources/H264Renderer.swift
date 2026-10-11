import AVFoundation
import CoreMedia
import UIKit

/// Annex-B H.264 access unit -> CMSampleBuffer -> AVSampleBufferDisplayLayer.
/// Katman donanım decoder'ı (VideoToolbox) kendisi kullanır; kareler `DisplayImmediately`
/// ile işaretlendiği için zaman damgası beklenmeden anında çizilir.
final class H264Renderer {
    let displayLayer = AVSampleBufferDisplayLayer()
    var onNeedKeyframe: (() -> Void)?

    private var sps: Data?
    private var pps: Data?
    private var formatDesc: CMVideoFormatDescription?

    init() {
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = UIColor.black.cgColor
    }

    /// Alıcı kuyruğundan çağrılır.
    func render(accessUnit: Data) {
        let bytes = [UInt8](accessUnit)
        var avcc = Data()
        var hasSlice = false

        for r in nalRanges(bytes) where !r.isEmpty {
            let type = bytes[r.lowerBound] & 0x1F
            switch type {
            case 7: updateParameterSet(sps: Data(bytes[r]), pps: nil)
            case 8: updateParameterSet(sps: nil, pps: Data(bytes[r]))
            case 1, 5:
                var len = UInt32(r.count).bigEndian
                withUnsafeBytes(of: &len) { avcc.append(contentsOf: $0) }
                avcc.append(contentsOf: bytes[r])
                hasSlice = true
            default: break // AUD, SEI vb.
            }
        }

        guard hasSlice else { return }
        guard let desc = formatDesc else { onNeedKeyframe?(); return }
        guard let sample = makeSampleBuffer(avcc: avcc, format: desc) else { return }

        if displayLayer.status == .failed {
            displayLayer.flush()
            onNeedKeyframe?()
            return
        }
        displayLayer.enqueue(sample)
    }

    // MARK: - Parametre setleri

    private func updateParameterSet(sps newSPS: Data?, pps newPPS: Data?) {
        var changed = false
        if let s = newSPS, s != sps { sps = s; changed = true }
        if let p = newPPS, p != pps { pps = p; changed = true }
        guard changed, let sps, let pps else { return }

        var desc: CMVideoFormatDescription?
        let status = sps.withUnsafeBytes { spsBuf in
            pps.withUnsafeBytes { ppsBuf -> OSStatus in
                let pointers = [
                    spsBuf.bindMemory(to: UInt8.self).baseAddress!,
                    ppsBuf.bindMemory(to: UInt8.self).baseAddress!,
                ]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: pointers,
                    parameterSetSizes: sizes,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &desc)
            }
        }
        if status == noErr { formatDesc = desc }
    }

    // MARK: - Sample buffer

    private func makeSampleBuffer(avcc: Data, format: CMVideoFormatDescription) -> CMSampleBuffer? {
        var block: CMBlockBuffer?
        let len = avcc.count
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: len,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil,
            offsetToData: 0, dataLength: len, flags: 0, blockBufferOut: &block) == noErr,
            let block else { return nil }

        let copied = avcc.withUnsafeBytes {
            CMBlockBufferReplaceDataBytes(
                with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: len)
        }
        guard copied == noErr else { return nil }

        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
            decodeTimeStamp: .invalid)
        var size = len
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
            sampleCount: 1, sampleTimingEntryCount: 1, sampleTimingArray: &timing,
            sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample) == noErr,
            let sample else { return nil }

        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dict,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }

    // MARK: - Annex-B ayrıştırma

    /// Start code'lardan (00 00 01 / 00 00 00 01) sonraki NAL aralıkları.
    private func nalRanges(_ b: [UInt8]) -> [Range<Int>] {
        var ranges: [Range<Int>] = []
        var start = -1
        var i = 0
        let n = b.count
        while i + 2 < n {
            if b[i] == 0, b[i + 1] == 0, b[i + 2] == 1 {
                if start >= 0 {
                    var end = i
                    if end > start, b[end - 1] == 0 { end -= 1 } // 4 byte start code'un baştaki sıfırı
                    ranges.append(start..<end)
                }
                start = i + 3
                i += 3
            } else {
                i += 1
            }
        }
        if start >= 0, start < n { ranges.append(start..<n) }
        return ranges
    }
}
