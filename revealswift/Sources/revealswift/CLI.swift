import Foundation
import RevealSwiftCore

#if canImport(WebKit) && canImport(AppKit)
import WebKit
import AppKit
import CoreGraphics
#endif

@main
struct RevealSwiftCLI {
    static func main() async {
        do {
            try await run(Array(CommandLine.arguments.dropFirst()))
        } catch {
            FileHandle.standardError.write(Data("revealswift: \(error)\n".utf8))
            exit(1)
        }
    }

    static func run(_ args: [String]) async throws {
        guard let command = args.first else { printHelp(); return }
        switch command {
        case "version":
            print("revealswift 0.1.0 (Reveal.js 6.0.2, Chart.js 4.5.1)")
        case "themes":
            try runThemes(Array(args.dropFirst()))
        case "deck":
            try await runDeck(Array(args.dropFirst()))
        case "slide":
            try await runSlide(Array(args.dropFirst()))
        case "inspect":
            try await renderCommand(Array(args.dropFirst()), mode: .inspect)
        case "screenshots":
            try await renderCommand(Array(args.dropFirst()), mode: .screenshots)
        case "pdf":
            try await renderCommand(Array(args.dropFirst()), mode: .pdf)
        case "review":
            try await renderCommand(Array(args.dropFirst()), mode: .review)
        case "help", "--help", "-h":
            printHelp()
        default:
            throw CLIError("Unknown command: \(command)")
        }
    }

    static func runThemes(_ args: [String]) throws {
        guard args.first == "validate", args.count >= 2 else {
            throw CLIError("Usage: revealswift themes validate <theme-dir>")
        }
        let theme = try ThemeLoader.load(at: URL(fileURLWithPath: args[1], isDirectory: true))
        print(String(decoding: try JSONIO.encode(theme.manifest), as: UTF8.self))
    }

    enum Mode { case inspect, screenshots, pdf, review }

    static func renderCommand(_ args: [String], mode: Mode) async throws {
        guard let input = args.first else { throw CLIError("Missing deck HTML path") }
        let options = Options(args: Array(args.dropFirst()))
        let frames = options.csvInts("--animation-frames")

        #if canImport(WebKit) && canImport(AppKit)
        let deckURL = URL(fileURLWithPath: input)
        let themePath = try resolveThemePath(explicit: options.value("--theme"), deckURL: deckURL)
        let runner = try await WebKitRunner(
            deck: deckURL,
            themePath: themePath
        )
        switch mode {
        case .inspect:
            let report = try await runner.review(output: nil, screenshots: false, pdf: false, animationFrames: frames)
            try emit(report: report, options: options)
        case .screenshots:
            let dir = URL(fileURLWithPath: options.value("--output") ?? "screenshots", isDirectory: true)
            let report = try await runner.review(output: dir, screenshots: true, pdf: false, animationFrames: frames)
            try emit(report: report, options: options)
        case .pdf:
            let file = URL(fileURLWithPath: options.value("--output") ?? "deck.pdf")
            try await runner.exportPDF(to: file)
            print(file.path)
        case .review:
            let dir = URL(fileURLWithPath: options.value("--output") ?? ".review", isDirectory: true)
            let report = try await runner.review(output: dir, screenshots: true, pdf: options.has("--pdf"), animationFrames: frames)
            try emit(report: report, options: options)
        }
        #else
        throw CLIError("Rendering requires macOS with WebKit. Core/theme tests can run on this platform.")
        #endif
    }

    static func resolveThemePath(explicit: String?, deckURL: URL) throws -> String? {
        if let explicit { return explicit }

        let html = try String(contentsOf: deckURL, encoding: .utf8)
        guard let name = RevealDeckFactory.embeddedThemeName(in: html), !name.isEmpty else { return nil }

        let fm = FileManager.default
        let executable = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
        let candidates = [
            deckURL.deletingLastPathComponent().appendingPathComponent("themes").appendingPathComponent(name),
            deckURL.deletingLastPathComponent().appendingPathComponent("Themes").appendingPathComponent(name),
            URL(fileURLWithPath: fm.currentDirectoryPath).appendingPathComponent("Themes").appendingPathComponent(name),
            executable.deletingLastPathComponent().appendingPathComponent("themes").appendingPathComponent(name),
            executable.deletingLastPathComponent().appendingPathComponent("../themes").appendingPathComponent(name).standardizedFileURL
        ]

        for candidate in candidates {
            if fm.fileExists(atPath: candidate.appendingPathComponent("theme.json").path) {
                return candidate.path
            }
        }

        throw CLIError("Deck requests theme '(name)' but no matching theme directory was found. Use --theme <dir> or place it under ./Themes/(name) or next to the binary under themes/(name).")
    }

    static func emit(report: ReviewReport, options: Options) throws {
        print(String(decoding: try JSONIO.encode(report), as: UTF8.self))
        if options.has("--strict") || options.has("--fail-on-warnings") {
            if report.errors > 0 || report.warnings > 0 {
                throw CLIError("QA failed: \(report.errors) error(s), \(report.warnings) warning(s)")
            }
        } else if options.has("--fail-on-errors"), report.errors > 0 {
            throw CLIError("QA failed: \(report.errors) error(s)")
        }
    }

    static func printHelp() {
        print("""
        RevealSwift — Reveal.js authoring, rendering and QA for agents

        Usage:
          revealswift version
          revealswift deck ...
          revealswift slide ...
          revealswift themes validate <theme-dir>
          revealswift inspect <deck.html> [--theme <dir>] [--animation-frames 0,250,500] [--fail-on-errors|--strict]
          revealswift screenshots <deck.html> [--theme <dir>] [--output <dir>] [--animation-frames 0,250,500]
          revealswift pdf <deck.html> [--theme <dir>] [--output <file.pdf>]
          revealswift review <deck.html> [--theme <dir>] [--output <dir>] [--animation-frames 0,250,500] [--pdf] [--fail-on-errors|--strict]
        """)
    }
}

struct Options {
    let args: [String]
    func has(_ key: String) -> Bool { args.contains(key) }
    func value(_ key: String) -> String? {
        guard let index = args.firstIndex(of: key), index + 1 < args.count else { return nil }
        return args[index + 1]
    }
    func csvInts(_ key: String) -> [Int] {
        guard let raw = value(key) else { return [] }
        return raw.split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }.filter { $0 >= 0 }
    }
}

struct CLIError: Error, CustomStringConvertible {
    let message: String
    init(_ message: String) { self.message = message }
    var description: String { message }
}

#if canImport(WebKit) && canImport(AppKit)
@MainActor
final class RuntimeSchemeHandler: NSObject, WKURLSchemeHandler {
    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url,
              url.host == "runtime",
              let asset = EmbeddedRuntime.asset(path: url.path) else {
            urlSchemeTask.didFailWithError(
                NSError(domain: "RevealSwift.Runtime", code: 404,
                        userInfo: [NSLocalizedDescriptionKey: "Embedded runtime asset not found"])
            )
            return
        }

        let response = URLResponse(
            url: url,
            mimeType: asset.mimeType,
            expectedContentLength: asset.data.count,
            textEncodingName: "utf-8"
        )
        urlSchemeTask.didReceive(response)
        urlSchemeTask.didReceive(asset.data)
        urlSchemeTask.didFinish()
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}
}

struct DeckState: Sendable {
    let index: Int
    let h: Int
    let v: Int
    let fragmentState: Int
}

@MainActor
final class WebKitRunner: NSObject, WKNavigationDelegate {
    private let webView: WKWebView
    private let theme: LoadedTheme?
    private let deckURL: URL
    private let width: Int
    private let height: Int
    private var navigationContinuation: CheckedContinuation<Void, Error>?

    init(deck: URL, themePath: String?) async throws {
        deckURL = deck
        theme = try themePath.map { try ThemeLoader.load(at: URL(fileURLWithPath: $0, isDirectory: true)) }
        width = theme?.manifest.width ?? 1920
        height = theme?.manifest.height ?? 1080

        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.setURLSchemeHandler(RuntimeSchemeHandler(), forURLScheme: "revealswift")
        webView = WKWebView(
            frame: NSRect(x: 0, y: 0, width: width, height: height),
            configuration: config
        )
        super.init()
        webView.navigationDelegate = self
        try await load()
    }

    private func load() async throws {
        let raw = try String(contentsOf: deckURL, encoding: .utf8)
        let injected = HTMLInjector.inject(html: raw, themeCSS: theme?.combinedCSS, width: width, height: height)

        try await withCheckedThrowingContinuation { continuation in
            navigationContinuation = continuation
            webView.loadHTMLString(injected, baseURL: deckURL.deletingLastPathComponent())
        }
        try await waitUntilReady()
        try await Task.sleep(for: .milliseconds(350))
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        navigationContinuation?.resume()
        navigationContinuation = nil
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        navigationContinuation?.resume(throwing: error)
        navigationContinuation = nil
    }

    private func waitUntilReady() async throws {
        for _ in 0..<120 {
            if let error = try await webView.evaluateJavaScript("window.__revealswiftInitError") as? String,
               !error.isEmpty {
                throw CLIError("Reveal initialization failed: \\(error)")
            }
            let value = try await webView.evaluateJavaScript(
                "Boolean(window.__revealswiftReady && document.fonts.status === 'loaded')"
            )
            if let ready = value as? Bool, ready { return }
            try await Task.sleep(for: .milliseconds(50))
        }
        throw CLIError("Timed out waiting for Reveal.js and fonts to initialize")
    }

    private func decodeJSONObject(_ value: Any?) throws -> Any {
        guard let json = value as? String, let data = json.data(using: .utf8) else {
            throw CLIError("WebKit returned a non-JSON bridge value")
        }
        return try JSONSerialization.jsonObject(with: data)
    }

    private func allStates() async throws -> [DeckState] {
        let value = try await webView.evaluateJavaScript(InspectorScript.enumerateStates)
        guard let raw = try decodeJSONObject(value) as? [[String: Any]] else {
            throw CLIError("Could not decode slide state list")
        }

        var result: [DeckState] = []
        for row in raw {
            guard
                let index = (row["index"] as? NSNumber)?.intValue,
                let h = (row["h"] as? NSNumber)?.intValue,
                let fragments = (row["fragments"] as? NSNumber)?.intValue
            else { continue }

            let v = (row["v"] as? NSNumber)?.intValue ?? 0
            for state in 0...fragments {
                result.append(DeckState(index: index, h: h, v: v, fragmentState: state))
            }
        }

        if result.isEmpty {
            let diagnostics = try? await webView.evaluateJavaScript(
                "JSON.stringify({dom:document.querySelectorAll('.reveal .slides section').length,runtime:window.__revealswiftRuntime || null})"
            )
            let diagnosticText = diagnostics.map { String(describing: $0) } ?? "unavailable"
            throw CLIError("Reveal.js reported zero slides; diagnostics=\(diagnosticText)")
        }
        return result
    }

    private func go(to state: DeckState) async throws {
        _ = try await webView.evaluateJavaScript(
            "window.__revealswiftDeck.slide(\(state.h), \(state.v), \(state.fragmentState - 1)); true"
        )
        try await Task.sleep(for: .milliseconds(100))
    }

    private func setAnimationTime(_ milliseconds: Int?) async throws {
        guard let milliseconds else { return }
        _ = try await webView.evaluateJavaScript("""
        (() => {
          if (!document.getAnimations) return true;
          document.getAnimations({subtree:true}).forEach(a => {
            try { a.pause(); a.currentTime = \(milliseconds); } catch (_) {}
          });
          return true;
        })()
        """)
        try await Task.sleep(for: .milliseconds(20))
    }

    private func inspect(_ state: DeckState, animationTime: Int?) async throws -> SlideMetrics {
        let rules = theme?.manifest.rules ?? ThemeRules()
        let value = try await webView.evaluateJavaScript(InspectorScript.javascript(
            rules: rules,
            allowedComponents: theme?.manifest.allowedComponents ?? [],
            appearance: theme?.manifest.appearance
        ))
        guard let raw = try decodeJSONObject(value) as? [String: Any] else {
            throw CLIError("Could not decode inspection result")
        }

        let issues: [ReviewIssue] = (raw["issues"] as? [[String: Any]] ?? []).map { item in
            ReviewIssue(
                severity: Severity(rawValue: item["severity"] as? String ?? "warning") ?? .warning,
                rule: item["rule"] as? String ?? "unknown",
                message: item["message"] as? String ?? "",
                element: item["element"] as? String,
                amount: (item["amount"] as? NSNumber)?.doubleValue
            )
        }

        return SlideMetrics(
            index: state.index,
            horizontal: state.h,
            vertical: state.v,
            state: state.fragmentState,
            animationTimeMs: animationTime,
            words: (raw["words"] as? NSNumber)?.intValue ?? 0,
            characters: (raw["characters"] as? NSNumber)?.intValue ?? 0,
            elementCount: (raw["elementCount"] as? NSNumber)?.intValue ?? 0,
            occupancy: (raw["occupancy"] as? NSNumber)?.doubleValue ?? 0,
            smallestFontPx: (raw["smallestFontPx"] as? NSNumber)?.doubleValue ?? 0,
            issues: issues
        )
    }

    private func snapshot(to url: URL) async throws {
        let config = WKSnapshotConfiguration()
        config.rect = NSRect(x: 0, y: 0, width: width, height: height)
        config.snapshotWidth = NSNumber(value: width)
        let image = try await webView.takeSnapshot(configuration: config)
        guard
            let tiff = image.tiffRepresentation,
            let bitmap = NSBitmapImageRep(data: tiff),
            let png = bitmap.representation(using: .png, properties: [:])
        else { throw CLIError("Could not encode screenshot as PNG") }
        try png.write(to: url)
    }

    func review(output: URL?, screenshots: Bool, pdf: Bool, animationFrames: [Int]) async throws -> ReviewReport {
        if let output {
            try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
        }

        let screenshotDir = output?.appendingPathComponent("screenshots", isDirectory: true)
        if screenshots, let screenshotDir {
            try FileManager.default.createDirectory(at: screenshotDir, withIntermediateDirectories: true)
        }

        let states = try await allStates()
        let samples: [Int?] = animationFrames.isEmpty ? [nil] : animationFrames.map(Optional.some)
        var metrics: [SlideMetrics] = []
        var canonicalScreenshots: [Int: URL] = [:]

        for state in states {
            try await go(to: state)
            for sample in samples {
                try await setAnimationTime(sample)
                metrics.append(try await inspect(state, animationTime: sample))
                if screenshots, let screenshotDir {
                    let suffix = sample.map { String(format: "-t%04d", $0) } ?? ""
                    let name = String(format: "slide-%03d-h%02d-v%02d-state-%02d%@.png",
                                      state.index + 1, state.h, state.v, state.fragmentState, suffix)
                    let screenshotURL = screenshotDir.appendingPathComponent(name)
                    try await snapshot(to: screenshotURL)
                    canonicalScreenshots[state.index] = screenshotURL
                }
            }
        }

        var artifacts: [String: String] = [:]
        if screenshots {
            artifacts["screenshots"] = "screenshots/"
            if let output, !canonicalScreenshots.isEmpty {
                let contactSheetURL = output.appendingPathComponent("contact-sheet.png")
                try createContactSheet(
                    imageURLs: canonicalScreenshots.keys.sorted().compactMap { canonicalScreenshots[$0] },
                    output: contactSheetURL
                )
                artifacts["contactSheet"] = "contact-sheet.png"
            }
        }
        if pdf, let output {
            let pdfURL = output.appendingPathComponent("deck.pdf")
            try await exportPDF(to: pdfURL)
            artifacts["pdf"] = "deck.pdf"
        }
        if output != nil { artifacts["report"] = "report.json" }

        let scored = ReportScoring.score(metrics: metrics)
        let report = ReviewReport(
            deck: deckURL.lastPathComponent,
            theme: theme?.manifest.name,
            generatedAt: Date(),
            slides: Set(states.map(\.index)).count,
            states: metrics.count,
            errors: scored.errors,
            warnings: scored.warnings,
            score: scored.score,
            metrics: metrics,
            artifacts: artifacts
        )

        if let output {
            try JSONIO.encode(report).write(to: output.appendingPathComponent("report.json"))
        }
        return report
    }

    private func createContactSheet(imageURLs: [URL], output: URL) throws {
        guard !imageURLs.isEmpty else { return }

        let columns = min(4, imageURLs.count)
        let rows = Int(ceil(Double(imageURLs.count) / Double(columns)))
        let thumbnailWidth: CGFloat = 360
        let thumbnailHeight = thumbnailWidth * CGFloat(height) / CGFloat(width)
        let labelHeight: CGFloat = 28
        let gap: CGFloat = 20
        let inset: CGFloat = 24
        let cellHeight = thumbnailHeight + labelHeight
        let sheetSize = NSSize(
            width: inset * 2 + CGFloat(columns) * thumbnailWidth + CGFloat(max(0, columns - 1)) * gap,
            height: inset * 2 + CGFloat(rows) * cellHeight + CGFloat(max(0, rows - 1)) * gap
        )

        let sheet = NSImage(size: sheetSize)
        sheet.lockFocus()
        NSColor(calibratedWhite: 0.08, alpha: 1).setFill()
        NSRect(origin: .zero, size: sheetSize).fill()

        let labelAttributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 16, weight: .semibold),
            .foregroundColor: NSColor.white
        ]

        for (offset, url) in imageURLs.enumerated() {
            guard let image = NSImage(contentsOf: url) else { continue }
            let column = offset % columns
            let row = offset / columns
            let x = inset + CGFloat(column) * (thumbnailWidth + gap)
            let yFromTop = inset + CGFloat(row) * (cellHeight + gap)
            let y = sheetSize.height - yFromTop - cellHeight
            let imageRect = NSRect(x: x, y: y + labelHeight, width: thumbnailWidth, height: thumbnailHeight)
            image.draw(in: imageRect, from: .zero, operation: .sourceOver, fraction: 1)
            let label = "Slide \(offset + 1)" as NSString
            label.draw(at: NSPoint(x: x, y: y + 5), withAttributes: labelAttributes)
        }

        sheet.unlockFocus()
        guard let tiff = sheet.tiffRepresentation,
              let bitmap = NSBitmapImageRep(data: tiff),
              let png = bitmap.representation(using: .png, properties: [:]) else {
            throw CLIError("Could not encode contact sheet")
        }
        try png.write(to: output)
    }

    func exportPDF(to url: URL) async throws {
        let states = try await allStates()
        let grouped = Dictionary(grouping: states, by: \.index)
        let finalStates = grouped.compactMapValues { values in
            values.max { $0.fragmentState < $1.fragmentState }
        }

        let data = NSMutableData()
        var box = CGRect(x: 0, y: 0, width: width, height: height)
        guard
            let consumer = CGDataConsumer(data: data as CFMutableData),
            let context = CGContext(consumer: consumer, mediaBox: &box, nil)
        else { throw CLIError("Could not create PDF context") }

        for index in finalStates.keys.sorted() {
            guard let state = finalStates[index] else { continue }
            try await go(to: state)
            let config = WKSnapshotConfiguration()
            config.rect = NSRect(x: 0, y: 0, width: width, height: height)
            config.snapshotWidth = NSNumber(value: width)
            let image = try await webView.takeSnapshot(configuration: config)
            guard let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { continue }
            context.beginPDFPage(nil)
            context.draw(cgImage, in: box)
            context.endPDFPage()
        }

        context.closePDF()
        try (data as Data).write(to: url)
    }
}
#endif
