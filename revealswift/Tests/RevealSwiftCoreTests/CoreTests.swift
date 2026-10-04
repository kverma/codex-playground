import XCTest
@testable import RevealSwiftCore

final class CoreTests: XCTestCase {
    func testInjectorAddsOfflineRuntimeOnce() {
        let source = "<html><head></head><body><div class='reveal'><div class='slides'></div></div></body></html>"
        let once = HTMLInjector.inject(html: source, themeCSS: ":root{--x:1}")
        XCTAssertTrue(once.contains("revealswift://runtime/reveal.js"))
        XCTAssertTrue(once.contains("revealswift://runtime/chart.js"))
        XCTAssertTrue(once.contains("revealswift://runtime/plugin/markdown.js"))
        XCTAssertTrue(once.contains("revealswift://runtime/plugin/math.js"))
        XCTAssertTrue(once.contains("revealswift://runtime/katex"))
        XCTAssertTrue(once.contains("RevealMath?.KaTeX?.()"))
        XCTAssertTrue(once.contains("RevealSwiftPlugins"))
        XCTAssertTrue(once.contains("RevealSwiftConfig"))
        XCTAssertFalse(once.contains("cdn.jsdelivr.net"))
        XCTAssertFalse(once.contains("https://"))
        XCTAssertEqual(once.components(separatedBy: HTMLInjector.marker).count - 1, 1)
        XCTAssertEqual(HTMLInjector.inject(html: once, themeCSS: nil), once)
    }

    func testInjectorUsesOuterClosingBodyWhenSrcdocContainsBodyTag() {
        let source = """
        <html><head></head><body>
          <div class="reveal"><div class="slides"><section><h2>Iframe</h2>
            <iframe srcdoc="<!doctype html><html><body>nested</body></html>"></iframe>
          </section></div></div>
        </body></html>
        """
        let injected = HTMLInjector.inject(html: source, themeCSS: nil)
        let runtimeIndex = try! XCTUnwrap(injected.range(of: "revealswift://runtime/reveal.js")?.lowerBound)
        let srcdocCloseIndex = try! XCTUnwrap(injected.range(of: "nested</body></html>")?.upperBound)
        XCTAssertGreaterThan(runtimeIndex, srcdocCloseIndex)
        XCTAssertTrue(injected.contains("srcdoc=\"<!doctype html><html><body>nested</body></html>\""))
    }

    func testInjectorTargetsOutermostClosingTags() {
        let source = """
        <!doctype html>
        <html>
        <head><title>Outer</title></head>
        <body>
          <iframe srcdoc="<!doctype html><html><head></head><body><p>Inner</p></body></html>"></iframe>
          <div class="reveal"><div class="slides"><section>Slide</section></div></div>
        </body>
        </html>
        """
        let injected = HTMLInjector.inject(html: source, themeCSS: nil)
        let markerIndex = injected.range(of: HTMLInjector.marker)!.lowerBound
        let outerHeadClose = injected.range(of: "</head>", options: .caseInsensitive)!.lowerBound
        XCTAssertLessThan(markerIndex, outerHeadClose)

        let runtimeScript = "<script src=\"revealswift://runtime/reveal.js\"></script>"
        let scriptIndex = injected.range(of: runtimeScript)!.lowerBound
        let outerBodyClose = injected.range(of: "</body>", options: [.caseInsensitive, .backwards])!.lowerBound
        XCTAssertLessThan(scriptIndex, outerBodyClose)

        let iframeClose = injected.range(of: "</iframe>")!.upperBound
        XCTAssertGreaterThan(scriptIndex, iframeClose)
        XCTAssertEqual(injected.components(separatedBy: runtimeScript).count - 1, 1)
    }

    func testEmbeddedRuntimeDecodesPinnedAssets() throws {
        let reveal = try XCTUnwrap(EmbeddedRuntime.asset(path: "/reveal.js"))
        let chart = try XCTUnwrap(EmbeddedRuntime.asset(path: "/chart.js"))
        let markdown = try XCTUnwrap(EmbeddedRuntime.asset(path: "/plugin/markdown.js"))
        let math = try XCTUnwrap(EmbeddedRuntime.asset(path: "/plugin/math.js"))
        let katex = try XCTUnwrap(EmbeddedRuntime.asset(path: "/katex/dist/katex.min.js"))
        let katexCSS = try XCTUnwrap(EmbeddedRuntime.asset(path: "/katex/dist/katex.min.css"))
        let autoRender = try XCTUnwrap(EmbeddedRuntime.asset(path: "/katex/dist/contrib/auto-render.min.js"))
        let katexFont = try XCTUnwrap(EmbeddedRuntime.asset(path: "/katex/dist/fonts/KaTeX_Main-Regular.woff2"))
        XCTAssertGreaterThan(reveal.data.count, 100_000)
        XCTAssertGreaterThan(chart.data.count, 150_000)
        XCTAssertGreaterThan(markdown.data.count, 20_000)
        XCTAssertGreaterThan(math.data.count, 3_000)
        XCTAssertGreaterThan(katex.data.count, 250_000)
        XCTAssertGreaterThan(katexCSS.data.count, 20_000)
        XCTAssertGreaterThan(autoRender.data.count, 3_000)
        XCTAssertGreaterThan(katexFont.data.count, 20_000)
        XCTAssertEqual(reveal.mimeType, "text/javascript")
        XCTAssertEqual(katexFont.mimeType, "font/woff2")
        XCTAssertEqual(EmbeddedRuntime.revealVersion, "6.0.2")
        XCTAssertEqual(EmbeddedRuntime.chartVersion, "4.5.1")
        XCTAssertEqual(EmbeddedRuntime.katexVersion, "0.19.0")
    }

    func testThemeLoadAndValidation() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let manifest = ThemeManifest(name: "test", displayName: "Test")
        try JSONIO.encode(manifest).write(to: dir.appendingPathComponent("theme.json"))
        try "body{}".write(to: dir.appendingPathComponent("theme.css"), atomically: true, encoding: .utf8)
        let loaded = try ThemeLoader.load(at: dir)
        XCTAssertEqual(loaded.manifest.name, "test")
        XCTAssertEqual(loaded.combinedCSS, "body{}")
    }

    func testScoring() {
        let metric = SlideMetrics(index: 0, state: 0, words: 1, characters: 1, elementCount: 1, occupancy: 0.5,
                                  smallestFontPx: 24, issues: [
            .init(severity: .error, rule: "x", message: "x"),
            .init(severity: .warning, rule: "y", message: "y")
        ])
        let result = ReportScoring.score(metrics: [metric])
        XCTAssertEqual(result.errors, 1)
        XCTAssertEqual(result.warnings, 1)
        XCTAssertEqual(result.score, 85)
    }

    func testRevealDeckFactoryCreatesNativeRevealHTML() {
        let html = RevealDeckFactory.create(
            title: "Atlas Review",
            theme: "dark-mode",
            outline: ["Title", "Architecture", "Metrics"]
        )
        XCTAssertTrue(html.contains("<div class=\"reveal\">"))
        XCTAssertTrue(html.contains("<div class=\"slides\">"))
        XCTAssertTrue(html.contains("<section id=\"title\" class=\"title-slide\">"))
        XCTAssertTrue(html.contains("<section id=\"architecture\">"))
        XCTAssertTrue(html.contains("<section id=\"metrics\">"))
        XCTAssertEqual(RevealDeckFactory.embeddedThemeName(in: html), "dark-mode")

        let changed = RevealDeckFactory.setEmbeddedThemeName("apple-inspired", in: html)
        XCTAssertEqual(RevealDeckFactory.embeddedThemeName(in: changed), "apple-inspired")
    }

    func testInspectorContainsOverlapAndThemeRules() {
        let js = InspectorScript.javascript(rules: ThemeRules())
        XCTAssertTrue(js.contains("layout.overlap"))
        XCTAssertTrue(js.contains("theme.inlineStyle"))
        XCTAssertTrue(js.contains("density.excessive"))
        XCTAssertTrue(js.contains("theme.safeMargin"))
        XCTAssertTrue(js.contains("theme.tooManyColumns"))
        XCTAssertTrue(js.contains("theme.unknownComponent"))
        XCTAssertTrue(js.contains("theme.appearanceMismatch"))
    }
}
