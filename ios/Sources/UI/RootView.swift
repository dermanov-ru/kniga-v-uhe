import SwiftUI

struct RootView: View {
    @EnvironmentObject private var container: AppContainer
    @EnvironmentObject private var player: Player
    @State private var showPlayer = false
    @State private var pendingBookId: Int?
    @State private var linkError: String?

    var body: some View {
        VStack(spacing: 0) {
            LibraryView(openBookId: $pendingBookId, showPlayer: $showPlayer)

            if player.bookId != nil {
                Divider()
                MiniPlayer { showPlayer = true }
            }
        }
        .sheet(isPresented: $showPlayer) {
            PlayerView()
        }
        .alert("Не удалось открыть ссылку", isPresented: .constant(linkError != nil)) {
            Button("Понятно") { linkError = nil }
        } message: {
            Text(linkError ?? "")
        }
        .task {
            // Restore the last book into the player on a cold start, paused and ready for one tap.
            player.openLastPlayed(play: false)
        }
        .onReceive(NotificationCenter.default.publisher(for: .incomingBookLink)) { note in
            guard let link = note.object as? String else { return }
            Task {
                do {
                    pendingBookId = try await container.api.resolveBookId(url: Api.normalize(link))
                } catch {
                    // A book whose rights moved to LitRes resolves fine but has no audio; say so
                    // instead of leaving the tap looking dead.
                    linkError = error.localizedDescription
                }
            }
        }
    }
}
