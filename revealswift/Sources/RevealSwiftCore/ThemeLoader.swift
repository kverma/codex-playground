import Foundation

public enum ThemeError: Error, CustomStringConvertible {
    case missingManifest(URL), missingCSS(String), invalidName, invalidViewport
    public var description: String {
        switch self {
        case .missingManifest(let url): return "Missing theme.json at \(url.path)"
        case .missingCSS(let file): return "Theme CSS not found: \(file)"
        case .invalidName: return "Theme name must not be empty"
        case .invalidViewport: return "Theme viewport must be positive"
        }
    }
}

public struct LoadedTheme: Sendable {
    public let root: URL
    public let manifest: ThemeManifest
    public let combinedCSS: String
}

public enum ThemeLoader {
    public static func load(at root: URL) throws -> LoadedTheme {
        let manifestURL = root.appendingPathComponent("theme.json")
        guard FileManager.default.fileExists(atPath: manifestURL.path) else { throw ThemeError.missingManifest(manifestURL) }
        let manifest = try JSONDecoder().decode(ThemeManifest.self, from: Data(contentsOf: manifestURL))
        guard !manifest.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw ThemeError.invalidName }
        guard manifest.width > 0, manifest.height > 0 else { throw ThemeError.invalidViewport }
        var chunks: [String] = []
        for css in manifest.css {
            let url = root.appendingPathComponent(css)
            guard FileManager.default.fileExists(atPath: url.path) else { throw ThemeError.missingCSS(css) }
            chunks.append(try String(contentsOf: url, encoding: .utf8))
        }
        return LoadedTheme(root: root, manifest: manifest, combinedCSS: chunks.joined(separator: "\n"))
    }
}
