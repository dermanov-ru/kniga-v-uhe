import SwiftUI

/// Cover art with a placeholder, used everywhere a book or a narrator is shown.
struct CoverImage: View {
    let url: String?
    var size: CGFloat?
    var corner: CGFloat = 10

    var body: some View {
        Group {
            if let url, let parsed = URL(string: url) {
                AsyncImage(url: parsed) { phase in
                    switch phase {
                    case let .success(image):
                        image.resizable().aspectRatio(contentMode: .fill)
                    default:
                        placeholder
                    }
                }
            } else {
                placeholder
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }

    private var placeholder: some View {
        ZStack {
            Color(.secondarySystemFill)
            Image(systemName: "book.closed")
                .foregroundStyle(.secondary)
        }
    }
}
