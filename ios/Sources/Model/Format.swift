import Foundation

enum Format {
    static func duration(_ ms: Int64) -> String {
        guard ms > 0 else { return "0:00" }
        let total = ms / 1000
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%d:%02d", m, s)
    }

    /// Wording for the library: «осталось 3 ч 12 мин».
    static func left(_ ms: Int64) -> String {
        let total = max(ms, 0) / 1000
        let h = total / 3600
        let m = (total % 3600) / 60
        switch (h, m) {
        case let (h, m) where h > 0 && m > 0: return "\(h) ч \(m) мин"
        case let (h, _) where h > 0: return "\(h) ч"
        case let (_, m) where m > 0: return "\(m) мин"
        default: return "меньше минуты"
        }
    }

    static func size(_ bytes: Int64) -> String {
        switch bytes {
        case 1_000_000_000...: return String(format: "%.1f ГБ", Double(bytes) / 1_000_000_000)
        case 1_000_000...: return String(format: "%.0f МБ", Double(bytes) / 1_000_000)
        case 1_000...: return String(format: "%.0f КБ", Double(bytes) / 1_000)
        default: return "\(bytes) Б"
        }
    }
}
