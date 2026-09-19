import SwiftUI

struct PlayerView: View {
    @EnvironmentObject private var store: LibraryStore
    @EnvironmentObject private var player: Player
    @Environment(\.dismiss) private var dismiss

    @State private var scrub: Double?
    @State private var showSleep = false
    @State private var showSpeed = false
    @State private var showChapters = false

    var body: some View {
        NavigationStack {
            if player.bookId == nil {
                Text("Ничего не играет")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                content
            }
        }
    }

    private var content: some View {
        VStack(spacing: 16) {
            CoverImage(url: player.cover, size: 240, corner: 16)
                .padding(.top, 12)

            VStack(spacing: 2) {
                Text(player.chapterTitle)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                Button("Глава \(player.chapterIndex + 1) из \(player.chapterCount)") {
                    showChapters = true
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            VStack(spacing: 4) {
                Slider(
                    value: Binding(
                        get: { scrub ?? fraction },
                        set: { scrub = $0 }
                    ),
                    in: 0...1,
                    onEditingChanged: { editing in
                        if !editing, let scrub {
                            player.seek(toMs: Int64(scrub * Double(max(player.durationMs, 1))))
                            self.scrub = nil
                        }
                    }
                )
                HStack {
                    Text(Format.duration(player.positionMs))
                    Spacer()
                    Text("-" + Format.duration(max(player.durationMs - player.positionMs, 0)))
                }
                .font(.caption2)
                .foregroundStyle(.secondary)

                Text("Книга: осталось \(Format.left(player.bookTotalMs - player.bookElapsedMs))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 24) {
                Button { player.previousChapter() } label: {
                    Image(systemName: "backward.end.fill")
                }
                .accessibilityLabel("Предыдущая глава")

                Button { player.skip(-Player.skipBack) } label: {
                    Image(systemName: "gobackward.10")
                }
                .accessibilityLabel("Назад 10 секунд")

                Button { player.playPause() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 34))
                        .frame(width: 76, height: 76)
                        .background(Circle().fill(.tint))
                        .foregroundStyle(.white)
                }
                .accessibilityLabel(player.isPlaying ? "Пауза" : "Играть")

                Button { player.skip(Player.skipForward) } label: {
                    Image(systemName: "goforward.30")
                }
                .accessibilityLabel("Вперёд 30 секунд")

                Button { player.nextChapter() } label: {
                    Image(systemName: "forward.end.fill")
                }
                .accessibilityLabel("Следующая глава")
            }
            .font(.title2)
            .buttonStyle(.plain)

            HStack(spacing: 12) {
                Button { showSpeed = true } label: {
                    Label(speedLabel, systemImage: "speedometer")
                }
                Button { showSleep = true } label: {
                    Label(player.sleep.label, systemImage: "moon.zzz")
                }
            }
            .buttonStyle(.bordered)
            .font(.subheadline)

            if let error = player.error {
                Text(error).font(.caption).foregroundStyle(.red)
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 24)
        .navigationTitle(player.bookTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { dismiss() } label: { Image(systemName: "chevron.down") }
                    .accessibilityLabel("Свернуть")
            }
        }
        .confirmationDialog("Скорость", isPresented: $showSpeed, titleVisibility: .visible) {
            ForEach(player.speedLevels, id: \.self) { level in
                Button(String(format: "%.2g×", level)) { player.setSpeed(level) }
            }
        }
        .confirmationDialog("Таймер сна", isPresented: $showSleep, titleVisibility: .visible) {
            ForEach(SleepOption.presets, id: \.self) { option in
                Button(option.label) { player.setSleep(option) }
            }
            if player.sleep != .off {
                Button("Выключить таймер", role: .destructive) { player.setSleep(.off) }
            }
        }
        .sheet(isPresented: $showChapters) { chapterList }
    }

    private var chapterList: some View {
        NavigationStack {
            List(Array(store.chapters(of: player.bookId ?? 0).enumerated()), id: \.element.id) { index, chapter in
                Button {
                    player.playChapter(index)
                    showChapters = false
                } label: {
                    HStack {
                        Text(chapter.title)
                            .foregroundStyle(index == player.chapterIndex ? Color.accentColor : .primary)
                            .lineLimit(1)
                        Spacer()
                        Text(Format.duration(chapter.durationMs))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
            }
            .navigationTitle("Главы")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var fraction: Double {
        guard player.durationMs > 0 else { return 0 }
        return min(max(Double(player.positionMs) / Double(player.durationMs), 0), 1)
    }

    private var speedLabel: String {
        String(format: "%.2g×", player.speed)
    }
}
