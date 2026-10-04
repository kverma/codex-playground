import Foundation

public struct EmbeddedRuntimeAsset: Sendable {
    public let data: Data
    public let mimeType: String

    public init(data: Data, mimeType: String) {
        self.data = data
        self.mimeType = mimeType
    }
}

public enum EmbeddedRuntime {
    public static let revealVersion = "6.0.2"
    public static let chartVersion = "4.5.1"
    public static let katexVersion = "0.19.0"

    private static func decode(_ value: String) -> Data? {
        Data(base64Encoded: value, options: [.ignoreUnknownCharacters])
    }

    private static func decode(parts: [String]) -> Data? {
        var data = Data()
        for part in parts {
            guard let decoded = decode(part) else { return nil }
            data.append(decoded)
        }
        return data
    }

    public static func asset(path rawPath: String) -> EmbeddedRuntimeAsset? {
        let path = rawPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let fontPrefix = "katex/dist/fonts/"
        if path.hasPrefix(fontPrefix) {
            let filename = String(path.dropFirst(fontPrefix.count))
            let encoded = RuntimeKaTeXFontsA.base64[filename] ?? RuntimeKaTeXFontsB.base64[filename]
            guard let encoded, let data = decode(encoded) else { return nil }
            return EmbeddedRuntimeAsset(data: data, mimeType: "font/woff2")
        }

        let value: (Data?, String)

        switch path {
        case "reveal.js":
            value = (decode(RuntimeRevealJS.base64), "text/javascript")
        case "reveal.css":
            value = (decode(RuntimeRevealCSS.base64), "text/css")
        case "chart.js":
            value = (decode(RuntimeChartJS.base64), "text/javascript")
        case "plugin/markdown.js":
            value = (decode(RuntimeMarkdownJS.base64), "text/javascript")
        case "plugin/highlight.js":
            value = (decode(parts: [
                RuntimeHighlightJSPart1.base64,
                RuntimeHighlightJSPart2.base64,
                RuntimeHighlightJSPart3.base64,
                RuntimeHighlightJSPart4.base64,
                RuntimeHighlightJSPart5.base64
            ]), "text/javascript")
        case "plugin/highlight.css":
            value = (decode(RuntimeHighlightCSS.base64), "text/css")
        case "plugin/notes.js":
            value = (decode(RuntimeNotesJS.base64), "text/javascript")
        case "plugin/math.js":
            value = (decode(RuntimeMathJS.base64), "text/javascript")
        case "plugin/search.js":
            value = (decode(RuntimeSearchJS.base64), "text/javascript")
        case "plugin/zoom.js":
            value = (decode(RuntimeZoomJS.base64), "text/javascript")
        case "katex/dist/katex.min.js":
            value = (decode(RuntimeKaTeX.jsBase64), "text/javascript")
        case "katex/dist/katex.min.css":
            value = (decode(RuntimeKaTeX.cssBase64), "text/css")
        case "katex/dist/contrib/auto-render.min.js":
            value = (decode(RuntimeKaTeX.autoRenderBase64), "text/javascript")
        default:
            return nil
        }

        guard let data = value.0 else { return nil }
        return EmbeddedRuntimeAsset(data: data, mimeType: value.1)
    }
}
