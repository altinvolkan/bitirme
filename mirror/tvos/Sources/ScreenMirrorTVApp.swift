import SwiftUI

@main
struct ScreenMirrorTVApp: App {
    @StateObject private var receiver = MirrorReceiver()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(receiver)
                .onAppear {
                    UIApplication.shared.isIdleTimerDisabled = true
                    receiver.start()
                }
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var receiver: MirrorReceiver

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoLayerView(displayLayer: receiver.renderer.displayLayer)
                .ignoresSafeArea()

            if !receiver.connected {
                VStack(spacing: 16) {
                    Text("Screen Mirror").font(.largeTitle)
                    Text(receiver.status)
                    Text("Adres: \(receiver.address)").font(.headline)
                }
                .foregroundColor(.white)
            } else {
                VStack {
                    Spacer()
                    HStack {
                        Text(receiver.stats)
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.6))
                        Spacer()
                    }
                }
                .padding(24)
            }
        }
    }
}
