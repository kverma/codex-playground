import Foundation

public enum JSONIO {
    public static func encode<T: Encodable>(_ value: T, pretty: Bool = true) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        if pretty { encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes] }
        return try encoder.encode(value)
    }
}
