import SwiftUI

/// Hand-rolled dependency graph; the app is small enough that a framework would be overhead.
@MainActor
final class AppContainer: ObservableObject {
    let api = Api()
    let settings = Settings()
    let store: LibraryStore
    let player = Player()
    let downloader = Downloader()

    init() {
        store = LibraryStore(api: api)
        player.configure(store: store)
        downloader.configure(store: store)
    }
}

@main
struct KnigaVUheApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var container = AppContainer()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(container)
                .environmentObject(container.store)
                .environmentObject(container.player)
                .environmentObject(container.downloader)
                .environmentObject(container.settings)
                .onAppear { delegate.container = container }
                .onOpenURL { url in
                    NotificationCenter.default.post(name: .incomingBookLink, object: url.absoluteString)
                }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    @MainActor var container: AppContainer?

    /// iOS wakes the app here when background chapter downloads finish.
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        Task { @MainActor in
            container?.downloader.backgroundCompletion = completionHandler
        }
    }
}

extension Notification.Name {
    static let incomingBookLink = Notification.Name("incomingBookLink")
}
