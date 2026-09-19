import SwiftUI

/// The bar above the tab area. It is a real bottom block, not an overlay, so the last row of any
/// list stays reachable however long the list gets.
struct MiniPlayer: View {
    @EnvironmentObject private var player: Player
    let onOpen: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ProgressView(value: fraction)
                .progressViewStyle(.linear)

            HStack(spacing: 12) {
                CoverImage(url: player.cover, size: 44, corner: 8)

                VStack(alignment: .leading, spacing: 2) {
                    Text(player.bookTitle)
                        .font(.subheadline)
                        .lineLimit(1)
                    Text(player.chapterTitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)

                Button {
                    player.playPause()
                } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title2)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(player.isPlaying ? "Пауза" : "Играть")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)
        }
        .background(.regularMaterial)
    }

    private var fraction: Double {
        guard player.durationMs > 0 else { return 0 }
        return min(max(Double(player.positionMs) / Double(player.durationMs), 0), 1)
    }
}
