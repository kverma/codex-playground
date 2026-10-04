import XCTest
@testable import RevealSwiftCore

final class CoreTests: XCTestCase {
    func testInjectorAddsRuntimeOnce() {
        let source = "<html><head></head><body><div class='reveal'><div class='slides'></div></div></body></html>"
        let once = HTMLInjector.inject(html: source, themeCSS: ":root{--x:1}")
        XCTAssertTrue(once.contains("reveal.js@6.0.2"))
        XCTAssertTrue(once.contains("chart.js@4.5.1"))
        XCTAssertEqual(once.components(separatedBy: HTMLInjector.marker).count - 1, 1)
        XCTAssertEqual(HTMLInjector.inject(html: once, themeCSS: nil), once)
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

    func testInspectorContainsOverlapAndThemeRules() {
        let js = InspectorScript.javascript(rules: ThemeRules())
        XCTAssertTrue(js.contains("layout.overlap"))
        XCTAssertTrue(js.contains("theme.inlineStyle"))
        XCTAssertTrue(js.contains("density.excessive"))
    }
}
