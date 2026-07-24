import SwiftUI
import UIKit
import ComposeApp

/// Bridges the Kotlin/Compose UI (`MainViewControllerKt.MainViewController()`)
/// into SwiftUI via `UIViewControllerRepresentable`.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController()
        controller.view.backgroundColor = UIColor(named: "LaunchBackground") ?? .systemBackground
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ZStack {
            Color("LaunchBackground")
                .ignoresSafeArea()

            ComposeView()
                .ignoresSafeArea(edges: .all)
        }
    }
}
