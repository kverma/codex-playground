import Foundation
import RevealSwiftCore

struct AuthoringDeckInfo: Codable {
    var title: String
    var theme: String?
    var slideCount: Int
    var slides: [RevealDeckOutlineItem]
}

struct AuthoringValidationReport: Codable {
    var valid: Bool
    var errors: [String]
    var warnings: [String]
    var slideCount: Int
}

#if canImport(WebKit) && canImport(AppKit)
import WebKit
import AppKit

@MainActor
final class RevealDOMEditor: NSObject, WKNavigationDelegate {
    private let webView: WKWebView
    private var continuation: CheckedContinuation<Void, Error>?

    override init() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        webView = WKWebView(frame: NSRect(x: 0, y: 0, width: 8, height: 8), configuration: config)
        super.init()
        webView.navigationDelegate = self
    }

    func prepare() async throws {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            webView.loadHTMLString("<!doctype html><html><body></body></html>", baseURL: nil)
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        continuation?.resume()
        continuation = nil
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        continuation?.resume(throwing: error)
        continuation = nil
    }

    func info(html: String) async throws -> AuthoringDeckInfo {
        let result = try await evaluateJSON("""
        (() => {
          const doc = new DOMParser().parseFromString(\(js(html)), 'text/html');
          const container = doc.querySelector('.reveal > .slides') || doc.querySelector('.reveal .slides');
          if (!container) return { error: 'Missing .reveal .slides container' };
          const slides = [...container.children].filter(el => el.tagName === 'SECTION');
          const theme = doc.querySelector('meta[name="revealswift-theme"]')?.getAttribute('content') || null;
          return {
            title: doc.title || '',
            theme,
            slideCount: slides.length,
            slides: slides.map((slide, i) => ({
              index: i + 1,
              id: slide.id || '',
              title: slide.querySelector(':scope > h1, :scope > h2, :scope > [data-rs-title]')?.textContent?.trim() || null,
              classes: [...slide.classList]
            }))
          };
        })()
        """)

        if let error = result["error"] as? String { throw CLIError(error) }
        let data = try JSONSerialization.data(withJSONObject: result)
        return try JSONDecoder().decode(AuthoringDeckInfo.self, from: data)
    }

    func validate(html: String) async throws -> AuthoringValidationReport {
        let result = try await evaluateJSON("""
        (() => {
          const doc = new DOMParser().parseFromString(\(js(html)), 'text/html');
          const errors = [], warnings = [];
          const reveal = doc.querySelector('.reveal');
          const container = doc.querySelector('.reveal > .slides') || doc.querySelector('.reveal .slides');
          if (!reveal) errors.push('Missing .reveal root');
          if (!container) errors.push('Missing .reveal .slides container');
          if (!container) return { valid:false, errors, warnings, slideCount:0 };

          const slides = [...container.children].filter(el => el.tagName === 'SECTION');
          if (slides.length === 0) errors.push('Deck has no top-level slides');

          const ids = new Map();
          slides.forEach((slide, i) => {
            if (!slide.id) warnings.push('Slide ' + (i + 1) + ' has no id; stable ids are recommended for agent editing');
            else {
              if (ids.has(slide.id)) errors.push('Duplicate slide id "' + slide.id + '" at slides ' + ids.get(slide.id) + ' and ' + (i + 1));
              ids.set(slide.id, i + 1);
            }
          });

          return { valid:errors.length === 0, errors, warnings, slideCount:slides.length };
        })()
        """)

        let data = try JSONSerialization.data(withJSONObject: result)
        return try JSONDecoder().decode(AuthoringValidationReport.self, from: data)
    }

    func getSlide(html: String, selector: String, outer: Bool) async throws -> String {
        let result = try await evaluateJSON("""
        (() => {
          const doc = new DOMParser().parseFromString(\(js(html)), 'text/html');
          const container = doc.querySelector('.reveal > .slides') || doc.querySelector('.reveal .slides');
          if (!container) return { error:'Missing .reveal .slides container' };
          const slide = \(slidePicker(selector));
          if (!slide) return { error:'Slide not found: ' + \(js(selector)) };
          return { value: \(outer ? "slide.outerHTML" : "slide.innerHTML") };
        })()
        """)
        if let error = result["error"] as? String { throw CLIError(error) }
        return result["value"] as? String ?? ""
    }

    func setSlide(html: String, selector: String, body: String) async throws -> String {
        try await mutate(html: html, script: """
        const slide = \(slidePicker(selector));
        if (!slide) return { error:'Slide not found: ' + \(js(selector)) };
        slide.innerHTML = \(js(body));
        """)
    }

    func addSlide(html: String, id: String, classes: [String], body: String, after: String?, before: String?) async throws -> String {
        let positionScript: String
        if let after {
            positionScript = """
            const target = \(slidePicker(after));
            if (!target) return { error:'Target slide not found: ' + \(js(after)) };
            target.after(slide);
            """
        } else if let before {
            positionScript = """
            const target = \(slidePicker(before));
            if (!target) return { error:'Target slide not found: ' + \(js(before)) };
            target.before(slide);
            """
        } else {
            positionScript = "container.appendChild(slide);"
        }

        let classScript = classes.isEmpty ? "" : "slide.className = \(js(classes.joined(separator: " ")));"

        return try await mutate(html: html, script: """
        if (doc.getElementById(\(js(id)))) return { error:'Duplicate id: ' + \(js(id)) };
        const slide = doc.createElement('section');
        slide.id = \(js(id));
        \(classScript)
        slide.innerHTML = \(js(body));
        \(positionScript)
        """)
    }

    func removeSlide(html: String, selector: String) async throws -> String {
        try await mutate(html: html, script: """
        const slide = \(slidePicker(selector));
        if (!slide) return { error:'Slide not found: ' + \(js(selector)) };
        slide.remove();
        """)
    }

    func moveSlide(html: String, selector: String, after: String?, before: String?) async throws -> String {
        guard after != nil || before != nil else { throw CLIError("slide move requires --after or --before") }
        let targetSelector = after ?? before!
        let placement = after != nil ? "target.after(slide);" : "target.before(slide);"

        return try await mutate(html: html, script: """
        const slide = \(slidePicker(selector));
        const target = \(slidePicker(targetSelector));
        if (!slide) return { error:'Slide not found: ' + \(js(selector)) };
        if (!target) return { error:'Target slide not found: ' + \(js(targetSelector)) };
        if (slide === target) return { error:'Slide and target are the same' };
        \(placement)
        """)
    }

    func duplicateSlide(html: String, selector: String, newID: String) async throws -> String {
        try await mutate(html: html, script: """
        const slide = \(slidePicker(selector));
        if (!slide) return { error:'Slide not found: ' + \(js(selector)) };
        if (doc.getElementById(\(js(newID)))) return { error:'Duplicate id: ' + \(js(newID)) };
        const clone = slide.cloneNode(true);
        clone.id = \(js(newID));
        slide.after(clone);
        """)
    }

    func renameSlide(html: String, selector: String, newID: String) async throws -> String {
        try await mutate(html: html, script: """
        const slide = \(slidePicker(selector));
        if (!slide) return { error:'Slide not found: ' + \(js(selector)) };
        const existing = doc.getElementById(\(js(newID)));
        if (existing && existing !== slide) return { error:'Duplicate id: ' + \(js(newID)) };
        slide.id = \(js(newID));
        """)
    }

    private func mutate(html: String, script: String) async throws -> String {
        let result = try await evaluateJSON("""
        (() => {
          const doc = new DOMParser().parseFromString(\(js(html)), 'text/html');
          const container = doc.querySelector('.reveal > .slides') || doc.querySelector('.reveal .slides');
          if (!container) return { error:'Missing .reveal .slides container' };
          \(script)
          return { html:'<!doctype html>\\n' + doc.documentElement.outerHTML };
        })()
        """)

        if let error = result["error"] as? String { throw CLIError(error) }
        guard let value = result["html"] as? String else { throw CLIError("DOM mutation did not return HTML") }
        return value
    }

    private func evaluateJSON(_ expression: String) async throws -> [String: Any] {
        let value = try await webView.evaluateJavaScript("JSON.stringify(\(expression))")
        guard let json = value as? String,
              let data = json.data(using: .utf8),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CLIError("Could not decode DOM editor response")
        }
        return object
    }

    private func slidePicker(_ selector: String) -> String {
        """
        (() => {
          const slides = [...container.children].filter(el => el.tagName === 'SECTION');
          const selector = \(js(selector));
          if (/^[0-9]+$/.test(selector)) {
            const n = Number(selector);
            return n >= 1 && n <= slides.length ? slides[n - 1] : null;
          }
          return slides.find(slide => slide.id === selector) || null;
        })()
        """
    }

    private func js(_ value: String) -> String {
        let data = try! JSONEncoder().encode(value)
        return String(decoding: data, as: UTF8.self)
    }
}
#endif

extension RevealSwiftCLI {
    @MainActor
    static func runDeck(_ args: [String]) async throws {
        guard let subcommand = args.first else { throw CLIError(deckUsage) }

        switch subcommand {
        case "create":
            guard args.count >= 2 else { throw CLIError(deckUsage) }
            let path = args[1]
            let options = Options(args: Array(args.dropFirst(2)))
            let url = URL(fileURLWithPath: path)

            if FileManager.default.fileExists(atPath: url.path), !options.has("--force") {
                throw CLIError("\(path) already exists; use --force to overwrite")
            }

            let title = options.value("--title") ?? url.deletingPathExtension().lastPathComponent
            let outline = options.value("--outline")?
                .split(separator: ",")
                .map { String($0).trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty } ?? []
            let slideCount = options.value("--slides").flatMap(Int.init)

            let html = RevealDeckFactory.create(
                title: title,
                theme: options.value("--theme"),
                outline: outline,
                slideCount: slideCount
            )
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try html.write(to: url, atomically: true, encoding: .utf8)
            print(url.path)

        case "info", "outline", "validate":
            guard args.count >= 2 else { throw CLIError(deckUsage) }
            #if canImport(WebKit) && canImport(AppKit)
            let url = URL(fileURLWithPath: args[1])
            let html = try String(contentsOf: url, encoding: .utf8)
            let editor = RevealDOMEditor()
            try await editor.prepare()

            if subcommand == "validate" {
                let report = try await editor.validate(html: html)
                print(String(decoding: try JSONIO.encode(report), as: UTF8.self))
                if !report.valid { throw CLIError("Deck structure is invalid") }
            } else {
                let info = try await editor.info(html: html)
                if subcommand == "outline" {
                    print(String(decoding: try JSONIO.encode(info.slides), as: UTF8.self))
                } else {
                    print(String(decoding: try JSONIO.encode(info), as: UTF8.self))
                }
            }
            #else
            throw CLIError("Deck DOM inspection requires macOS with WebKit")
            #endif

        case "set-theme":
            guard args.count >= 3 else { throw CLIError("Usage: revealswift deck set-theme <deck.html> <theme-name>") }
            let url = URL(fileURLWithPath: args[1])
            let html = try String(contentsOf: url, encoding: .utf8)
            let updated = RevealDeckFactory.setEmbeddedThemeName(args[2], in: html)
            try updated.write(to: url, atomically: true, encoding: .utf8)
            print(url.path)

        default:
            throw CLIError(deckUsage)
        }
    }

    @MainActor
    static func runSlide(_ args: [String]) async throws {
        guard let subcommand = args.first, args.count >= 2 else { throw CLIError(slideUsage) }
        let deckURL = URL(fileURLWithPath: args[1])
        let html = try String(contentsOf: deckURL, encoding: .utf8)

        let optionsStart: Int
        let selector: String?

        switch subcommand {
        case "list", "add":
            optionsStart = 2
            selector = nil
        default:
            guard args.count >= 3 else { throw CLIError(slideUsage) }
            optionsStart = 3
            selector = args[2]
        }

        let options = Options(args: Array(args.dropFirst(optionsStart)))

        #if canImport(WebKit) && canImport(AppKit)
        let editor = RevealDOMEditor()
        try await editor.prepare()

        switch subcommand {
        case "list":
            let info = try await editor.info(html: html)
            print(String(decoding: try JSONIO.encode(info.slides), as: UTF8.self))

        case "get":
            let value = try await editor.getSlide(html: html, selector: selector!, outer: options.has("--outer"))
            print(value)

        case "set":
            let body = try readContent(options)
            try writeMutation(
                try await editor.setSlide(html: html, selector: selector!, body: body),
                original: deckURL,
                options: options
            )

        case "add":
            guard let id = options.value("--id") else { throw CLIError("slide add requires --id") }
            let body = try readContent(options, allowEmpty: true)
            let classes = options.value("--class")?
                .split(separator: ",")
                .map { String($0).trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty } ?? []

            let updated = try await editor.addSlide(
                html: html,
                id: id,
                classes: classes,
                body: body,
                after: options.value("--after"),
                before: options.value("--before")
            )
            try writeMutation(updated, original: deckURL, options: options)

        case "remove":
            try writeMutation(
                try await editor.removeSlide(html: html, selector: selector!),
                original: deckURL,
                options: options
            )

        case "move":
            let updated = try await editor.moveSlide(
                html: html,
                selector: selector!,
                after: options.value("--after"),
                before: options.value("--before")
            )
            try writeMutation(updated, original: deckURL, options: options)

        case "duplicate":
            guard let newID = options.value("--id") else { throw CLIError("slide duplicate requires --id <new-id>") }
            try writeMutation(
                try await editor.duplicateSlide(html: html, selector: selector!, newID: newID),
                original: deckURL,
                options: options
            )

        case "rename":
            guard let newID = options.value("--id") else { throw CLIError("slide rename requires --id <new-id>") }
            try writeMutation(
                try await editor.renameSlide(html: html, selector: selector!, newID: newID),
                original: deckURL,
                options: options
            )

        default:
            throw CLIError(slideUsage)
        }
        #else
        throw CLIError("Slide editing requires macOS with WebKit")
        #endif
    }

    private static func readContent(_ options: Options, allowEmpty: Bool = false) throws -> String {
        if let inline = options.value("--html") { return inline }
        if let path = options.value("--file") {
            return try String(contentsOfFile: path, encoding: .utf8)
        }
        if options.has("--stdin") {
            let data = FileHandle.standardInput.readDataToEndOfFile()
            return String(decoding: data, as: UTF8.self)
        }
        if allowEmpty { return "" }
        throw CLIError("Provide slide content with --stdin, --html '<...>', or --file fragment.html")
    }

    private static func writeMutation(_ html: String, original: URL, options: Options) throws {
        let output = options.value("--output").map(URL.init(fileURLWithPath:)) ?? original
        try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
        try html.write(to: output, atomically: true, encoding: .utf8)
        print(output.path)
    }

    static let deckUsage = """
    Usage:
      revealswift deck create <deck.html> --title <title> [--theme <name>] [--slides N | --outline id,id,...] [--force]
      revealswift deck info <deck.html>
      revealswift deck outline <deck.html>
      revealswift deck validate <deck.html>
      revealswift deck set-theme <deck.html> <theme-name>
    """

    static let slideUsage = """
    Usage:
      revealswift slide list <deck.html>
      revealswift slide get <deck.html> <id|index> [--outer]
      revealswift slide set <deck.html> <id|index> (--stdin | --html <fragment> | --file <fragment.html>)
      revealswift slide add <deck.html> --id <id> [--after <id|index> | --before <id|index>] [--class a,b] [--stdin|--html|--file]
      revealswift slide remove <deck.html> <id|index>
      revealswift slide move <deck.html> <id|index> (--after <id|index> | --before <id|index>)
      revealswift slide duplicate <deck.html> <id|index> --id <new-id>
      revealswift slide rename <deck.html> <id|index> --id <new-id>
    """
}
