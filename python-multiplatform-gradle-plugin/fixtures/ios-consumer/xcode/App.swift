import SwiftUI
import Probe

@main
struct ConsumerApp: App {
    init() {
        ProbeMainKt.runProbe()
    }

    var body: some Scene {
        WindowGroup {
            Text("ios-consumer probe: see the console")
        }
    }
}
