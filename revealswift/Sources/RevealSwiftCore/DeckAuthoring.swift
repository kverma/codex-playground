import Foundation

public struct DeckDocument: Codable, Sendable, Equatable {
    public var schemaVersion: Int
    public var title: String
    public var theme: String?
    public var slides: [DeckSlide]

    public init(schemaVersion: Int = 1, title: String, theme: String? = nil, slides: [DeckSlide] = []) {
        self.schemaVersion = schemaVersion
        self.title = title
        self.theme = theme
        self.slides = slides
    }
}

public struct DeckSlide: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var kind: String
    public var title: String?
    public var subtitle: String?
    public var eyebrow: String?
    public var classes: [String]
    public var blocks: [DeckBlock]
    public var notes: String?

    public init(id: String = UUID().uuidString.lowercased(),
                kind: String = "content",
                title: String? = nil,
                subtitle: String? = nil,
                eyebrow: String? = nil,
                classes: [String] = [],
                blocks: [DeckBlock] = [],
                notes: String? = nil) {
        self.id = id
        self.kind = kind
        self.title = title
        self.subtitle = subtitle
        self.eyebrow = eyebrow
        self.classes = classes
        self.blocks = blocks
        self.notes = notes
    }
}

public struct DeckBlock: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var type: String
    public var heading: String?
    public var text: String?
    public var items: [String]
    public var value: String?
    public var label: String?
    public var html: String?
    public var language: String?
    public var chart: DeckChart?
    public var fragment: Bool
    public var classes: [String]
    public var component: String?

    public init(id: String = UUID().uuidString.lowercased(),
                type: String,
                heading: String? = nil,
                text: String? = nil,
                items: [String] = [],
                value: String? = nil,
                label: String? = nil,
                html: String? = nil,
                language: String? = nil,
                chart: DeckChart? = nil,
                fragment: Bool = false,
                classes: [String] = [],
                component: String? = nil) {
        self.id = id
        self.type = type
        self.heading = heading
        self.text = text
        self.items = items
        self.value = value
        self.label = label
        self.html = html
        self.language = language
        self.chart = chart
        self.fragment = fragment
        self.classes = classes
        self.component = component
    }
}

public struct DeckChart: Codable, Sendable, Equatable {
    public var type: String
    public var labels: [String]
    public var values: [Double]
    public var datasetLabel: String?

    public init(type: String = "bar", labels: [String], values: [Double], datasetLabel: String? = nil) {
        self.type = type
        self.labels = labels
        self.values = values
        self.datasetLabel = datasetLabel
    }
}

public enum DeckAuthoringError: Error, CustomStringConvertible {
    case unsupportedSchema(Int)
    case unsupportedSlideKind(String)
    case unsupportedBlockType(String)
    case slideNotFound(String)
    case blockNotFound(String)
    case invalidChart

    public var description: String {
        switch self {
        case .unsupportedSchema(let version): return "Unsupported deck schema version: \(version)"
        case .unsupportedSlideKind(let kind): return "Unsupported slide kind: \(kind)"
        case .unsupportedBlockType(let type): return "Unsupported block type: \(type)"
        case .slideNotFound(let selector): return "Slide not found: \(selector)"
        case .blockNotFound(let selector): return "Block not found: \(selector)"
        case .invalidChart: return "Chart labels and values must have the same non-zero count"
        }
    }
}

public enum DeckStore {
    public static func load(from url: URL) throws -> DeckDocument {
        let deck = try JSONDecoder().decode(DeckDocument.self, from: Data(contentsOf: url))
        guard deck.schemaVersion == 1 else { throw DeckAuthoringError.unsupportedSchema(deck.schemaVersion) }
        return deck
    }

    public static func save(_ deck: DeckDocument, to url: URL) throws {
        let data = try JSONIO.encode(deck)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url)
    }

    public static func slideIndex(in deck: DeckDocument, selector: String) throws -> Int {
        if let number = Int(selector), number >= 1, number <= deck.slides.count {
            return number - 1
        }
        if let index = deck.slides.firstIndex(where: { $0.id == selector }) {
            return index
        }
        throw DeckAuthoringError.slideNotFound(selector)
    }

    public static func blockIndex(in slide: DeckSlide, selector: String) throws -> Int {
        if let number = Int(selector), number >= 1, number <= slide.blocks.count {
            return number - 1
        }
        if let index = slide.blocks.firstIndex(where: { $0.id == selector }) {
            return index
        }
        throw DeckAuthoringError.blockNotFound(selector)
    }
}

public enum DeckCompiler {
    public static let supportedSlideKinds = ["title", "section", "content", "custom"]
    public static let supportedBlockTypes = ["text", "bullets", "card", "callout", "metric", "code", "html", "chart"]

    public static func compile(_ deck: DeckDocument) throws -> String {
        for slide in deck.slides {
            guard supportedSlideKinds.contains(slide.kind) else { throw DeckAuthoringError.unsupportedSlideKind(slide.kind) }
        }

        let sections = try deck.slides.map(renderSlide).joined(separator: "\n")
        return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>\(escape(deck.title))</title>
          <meta name="revealswift-schema" content="1">
          \(deck.theme.map { "<meta name=\"revealswift-theme\" content=\"\(escapeAttribute($0))\">" } ?? "")
        </head>
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

    private static func renderSlide(_ slide: DeckSlide) throws -> String {
        var classes = slide.classes
        if slide.kind == "title" { classes.append("title-slide") }
        if slide.kind == "section" { classes.append("section-slide") }
        classes = Array(Set(classes)).sorted()

        var content: [String] = []
        if let eyebrow = slide.eyebrow, !eyebrow.isEmpty {
            content.append("<p class=\"eyebrow\">\(escape(eyebrow))</p>")
        }

        if let title = slide.title, !title.isEmpty {
            content.append(slide.kind == "title" ? "<h1>\(escape(title))</h1>" : "<h2>\(escape(title))</h2>")
        }

        if let subtitle = slide.subtitle, !subtitle.isEmpty {
            content.append("<p class=\"subtitle\">\(escape(subtitle))</p>")
        }

        for block in slide.blocks {
            content.append(try renderBlock(block))
        }

        if let notes = slide.notes, !notes.isEmpty {
            content.append("<aside class=\"notes\">\(escape(notes))</aside>")
        }

        let classAttribute = classes.isEmpty ? "" : " class=\"\(classes.map(escapeAttribute).joined(separator: " "))\""
        return """
              <section data-rs-slide-id="\(escapeAttribute(slide.id))" data-rs-kind="\(escapeAttribute(slide.kind))"\(classAttribute)>
                \(content.joined(separator: "\n        "))
              </section>
        """
    }

    private static func renderBlock(_ block: DeckBlock) throws -> String {
        guard supportedBlockTypes.contains(block.type) else { throw DeckAuthoringError.unsupportedBlockType(block.type) }

        var classes = block.classes
        if block.fragment { classes.append("fragment") }
        let component = block.component ?? defaultComponent(for: block.type)
        let componentAttribute = component.map { " data-rs-component=\"\(escapeAttribute($0))\"" } ?? ""

        switch block.type {
        case "text":
            classes.append("rs-text")
            return wrap(tag: "p", classes: classes, componentAttribute: componentAttribute, body: escape(block.text ?? ""))
        case "bullets":
            classes.append("rs-bullets")
            let items = block.items.map { "<li>\(escape($0))</li>" }.joined()
            return wrap(tag: "ul", classes: classes, componentAttribute: componentAttribute, body: items)
        case "card", "callout":
            classes.append(block.type)
            var body = ""
            if let heading = block.heading, !heading.isEmpty { body += "<h3>\(escape(heading))</h3>" }
            if let text = block.text, !text.isEmpty { body += "<p>\(escape(text))</p>" }
            return wrap(tag: "div", classes: classes, componentAttribute: componentAttribute, body: body)
        case "metric":
            classes.append("metric")
            let value = escape(block.value ?? "")
            let label = escape(block.label ?? "")
            let body = "<div class=\"metric-value\">\(value)</div><p>\(label)</p>"
            return wrap(tag: "div", classes: classes, componentAttribute: componentAttribute, body: body)
        case "code":
            let language = escapeAttribute(block.language ?? "text")
            let classAttribute = classes.isEmpty ? "" : " \(classes.map(escapeAttribute).joined(separator: " "))"
            return "<pre\(componentAttribute)><code class=\"language-\(language)\(classAttribute)\" data-trim>\(escape(block.text ?? ""))</code></pre>"
        case "html":
            return block.html ?? ""
        case "chart":
            guard let chart = block.chart,
                  !chart.labels.isEmpty,
                  chart.labels.count == chart.values.count else { throw DeckAuthoringError.invalidChart }
            classes.append("chart")
            let canvasID = "rs-chart-" + block.id.replacingOccurrences(of: "-", with: "")
            let labels = try jsonLiteral(chart.labels)
            let values = try jsonLiteral(chart.values)
            let dataset = try jsonLiteral(chart.datasetLabel ?? "Series")
            let body = """
            <canvas id="\(escapeAttribute(canvasID))" data-rs-inline-ok></canvas>
            <script>
            (() => {
              const render = () => {
                if (!window.Chart) return setTimeout(render, 25);
                const s = getComputedStyle(document.documentElement);
                const palette = [1,2,3,4,5,6].map(i => s.getPropertyValue('--rs-chart-' + i).trim()).filter(Boolean);
                new Chart(document.getElementById('\(escapeAttribute(canvasID))'), {
                  type: \(try jsonLiteral(chart.type)),
                  data: { labels: \(labels), datasets: [{ label: \(dataset), data: \(values), backgroundColor: palette }] },
                  options: { responsive: true, maintainAspectRatio: false, animation: false }
                });
              };
              render();
            })();
            </script>
            """
            return wrap(tag: "div", classes: classes, componentAttribute: componentAttribute, body: body)
        default:
            throw DeckAuthoringError.unsupportedBlockType(block.type)
        }
    }

    private static func defaultComponent(for type: String) -> String? {
        ["card", "callout", "metric", "chart"].contains(type) ? type : nil
    }

    private static func wrap(tag: String, classes: [String], componentAttribute: String, body: String) -> String {
        let unique = Array(Set(classes)).sorted()
        let classAttribute = unique.isEmpty ? "" : " class=\"\(unique.map(escapeAttribute).joined(separator: " "))\""
        return "<\(tag)\(classAttribute)\(componentAttribute)>\(body)</\(tag)>"
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

    private static func jsonLiteral<T: Encodable>(_ value: T) throws -> String {
        let data = try JSONEncoder().encode(value)
        return String(decoding: data, as: UTF8.self)
    }
}
