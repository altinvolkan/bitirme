import Foundation

/// Tel protokolü (Android tarafındaki `Protocol.kt` ile birebir aynı olmalı).
///
/// Video paketi (big-endian):
///   [0] type=1  [1] flags (bit0 = keyframe)  [2..5] frameId u32
///   [6..7] fragIdx u16  [8..9] fragCount u16  [10..] H.264 Annex-B parçası
/// Geri kanal: tek byte, type=2 => "keyframe gönder".
enum Proto {
    static let port: UInt16 = 52000
    static let serviceType = "_scrmirror._udp"

    static let typeVideo: UInt8 = 1
    static let typeKeyframeRequest: UInt8 = 2
    static let flagKeyframe: UInt8 = 1
    static let headerSize = 10
}
