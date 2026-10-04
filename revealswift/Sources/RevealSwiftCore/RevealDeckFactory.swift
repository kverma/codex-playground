import Foundation

public struct RevealDeckOutlineItem: Codable, Sendable, Equatable {
    public var index: Int
    public var id: String
    public var title: String?
    public var classes: [String]

    public init(index: Int, id: String, title: String?, classes: [String]) {
        self.index = index
        self.id = id
        self.title = title
        self.classes = classes
    }
}

public enum RevealDeckFactory {
    public static func create(title: String, theme: String? = nil, outline: [String] = [], slideCount: Int? = nil) -> String {
        let ids: [String]
        if !outline.isEmpty {
            ids = uniqueIDs(outline.map(slug))
        } else {
            let count = max(1, slideCount ?? 1)
            ids = (0..<count).map { $0 == 0 ? "title" : "slide-\($0 + 1)" }
        }

        let sections = ids.enumerated().map { offset, id in
            let isTitle = offset == 0
            let klass = isTitle ? " class=\"title-slide\"" : ""
            let placeholder = isTitle
                ? "<h1>\(escape(title))</h1>"
                : "<h2>\(escape(humanize(id)))</h2>"
            return "      <section id=\"\(escapeAttribute(id))\"\(klass)>\n        \(placeholder)\n      </section>"
        }.joined(separator: "\n\n")

        let themeMeta = theme.map {
            "  <meta name=\"revealswift-theme\" content=\"\(escapeAttribute($0))\">\n"
        } ?? ""

        return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>\(escape(title))</title>
        \(themeMeta)</head>
        <body>
          <div class="reveal">
            <div class="slides">
        \(sections)
            </div>
          </div>
        </body>
        </html>
        """
    }

    public static func embeddedThemeName(in html: String) -> String? {
        let pattern = #"<meta\s+[^>]*name\s*=\s*["']revealswift-theme["'][^>]*content\s*=\s*["']([^"']+)["'][^>]*>"#
        if let value = firstCapture(pattern: pattern, in: html) { return value }

        let reversed = #"<meta\s+[^>]*content\s*=\s*["']([^"']+)["'][^>]*name\s*=\s*["']revealswift-theme["'][^>]*>"#
        return firstCapture(pattern: reversed, in: html)
    }

    public static func setEmbeddedThemeName(_ theme: String?, in html: String) -> String {
        let existing = #"<meta\s+[^>]*name\s*=\s*["']revealswift-theme["'][^>]*>"#
        let reversed = #"<meta\s+[^>]*content\s*=\s*["'][^"']+["'][^>]*name\s*=\s*["']revealswift-theme["'][^>]*>"#

        var result = html.replacingOccurrences(of: existing, with: "", options: .regularExpression)
        result = result.replacingOccurrences(of: reversed, with: "", options: .regularExpression)

        guard let theme, !theme.isEmpty else { return result }
        let meta = "<meta name=\"revealswift-theme\" content=\"\(escapeAttribute(theme))\">"
        if let range = result.range(of: "</head>", options: .caseInsensitive) {
            result.insert(contentsOf: "  \(meta)\n", at: range.lowerBound)
            return result
        }
        return "<head>\(meta)</head>\n" + result
    }

    private static func firstCapture(pattern: String, in value: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let match = regex.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)),
              match.numberOfRanges > 1,
              let range = Range(match.range(at: 1), in: value) else { return nil }
        return String(value[range])
    }

    private static func slug(_ value: String) -> String {
        let lowered = value.lowercased()
        let pieces = lowered.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }
        return pieces.joined(separator: "-").isEmpty ? "slide" : pieces.joined(separator: "-")
    }

    private static func uniqueIDs(_ values: [String]) -> [String] {
        var counts: [String: Int] = [:]
        return values.map { base in
            let count = (counts[base] ?? 0) + 1
            counts[base] = count
            return count == 1 ? base : "\(base)-\(count)"
        }
    }

    private static func humanize(_ value: String) -> String {
        value.split(separator: "-").map { $0.capitalized }.joined(separator: " ")
    }

    private static func escape(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }

    private static func escapeAttribute(_ value: String) -> String {
        escape(value).replacingOccurrences(of: "'", with: "&#39;")
    }
}
