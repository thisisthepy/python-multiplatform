import SwiftUI
import ConsumerApp

@main
struct FixtureApp: App {
    // Once, when the app starts; the probe also prints every line for `simctl launch --console-pty`.
    let report = ProbeKt.runProbe()

    var body: some Scene {
        WindowGroup {
            ScrollView {
                Text(report)
                    .font(.system(.footnote, design: .monospaced))
                    .padding()
            }
        }
    }
}
