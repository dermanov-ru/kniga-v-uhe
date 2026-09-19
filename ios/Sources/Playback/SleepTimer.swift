import Foundation

enum SleepOption: Hashable {
    case minutes(Int)
    case endOfChapter
    case off

    static let presets: [SleepOption] = [.minutes(15), .minutes(30), .minutes(45), .minutes(60), .endOfChapter]

    var label: String {
        switch self {
        case let .minutes(value): return "\(value) минут"
        case .endOfChapter: return "До конца главы"
        case .off: return "Выключить"
        }
    }
}

enum SleepState: Equatable {
    case off
    case countdown(remainingMs: Int64)
    case endOfChapter

    var label: String {
        switch self {
        case .off: return "Таймер"
        case .endOfChapter: return "До конца главы"
        case let .countdown(remaining): return Format.duration(remaining)
        }
    }
}
