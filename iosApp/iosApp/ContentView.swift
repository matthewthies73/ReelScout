import UIKit
import SwiftUI
import composeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        // Compose lays out under the status bar and home indicator itself (Scaffold and
        // TopAppBar apply the safe-area insets), so SwiftUI mustn't inset it as well.
        ComposeView()
                .ignoresSafeArea()
    }
}



