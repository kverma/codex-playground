import Foundation

public enum Severity: String, Codable, Sendable { case info, warning, error }

public struct ThemeRules: Codable, Sendable, Equatable {
    public var minimumBodyFontPx: Double
    public var minimumCaptionFontPx: Double
    public var minimumMarginPx: Double
    public var maximumColumns: Int
    public var densityPreferredMax: Double
    public var densityWarningMax: Double
    public var densityMaximum: Double
    public var requireTitle: Bool

    public init(minimumBodyFontPx: Double = 24, minimumCaptionFontPx: Double = 16, minimumMarginPx: Double = 56,
                maximumColumns: Int = 3, densityPreferredMax: Double = 0.68, densityWarningMax: Double = 0.78,
                densityMaximum: Double = 0.88, requireTitle: Bool = true) {
        self.minimumBodyFontPx = minimumBodyFontPx
        self.minimumCaptionFontPx = minimumCaptionFontPx
        self.minimumMarginPx = minimumMarginPx
        self.maximumColumns = maximumColumns
        self.densityPreferredMax = densityPreferredMax
        self.densityWarningMax = densityWarningMax
        self.densityMaximum = densityMaximum
        self.requireTitle = requireTitle
    }
}

public struct ThemeManifest: Codable, Sendable, Equatable {
    public var name: String
    public var displayName: String
    public var version: String
    public var appearance: String?
    public var css: [String]
    public var allowedComponents: [String]
    public var width: Int
    public var height: Int
    public var rules: ThemeRules

    public init(name: String, displayName: String, version: String = "1.0.0", appearance: String? = nil,
                css: [String] = ["theme.css"], allowedComponents: [String] = [], width: Int = 1920,
                height: Int = 1080, rules: ThemeRules = .init()) {
        self.name = name
        self.displayName = displayName
        self.version = version
        self.appearance = appearance
        self.css = css
        self.allowedComponents = allowedComponents
        self.width = width
        self.height = height
        self.rules = rules
    }
}

public struct ReviewBounds: Codable, Sendable, Equatable {
    public var x: Double
    public var y: Double
    public var width: Double
    public var height: Double

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

public struct ReviewIssueTarget: Codable, Sendable, Equatable {
    public var selector: String
    public var text: String?
    public var bounds: ReviewBounds?

    public init(selector: String, text: String? = nil, bounds: ReviewBounds? = nil) {
        self.selector = selector
        self.text = text
        self.bounds = bounds
    }
}

public struct ReviewIssue: Codable, Sendable, Equatable {
    public var severity: Severity
    public var rule: String
    public var message: String
    public var element: String?
    public var amount: Double?
    public var targets: [ReviewIssueTarget]

    public init(severity: Severity, rule: String, message: String, element: String? = nil,
                amount: Double? = nil, targets: [ReviewIssueTarget] = []) {
        self.severity = severity
        self.rule = rule
        self.message = message
        self.element = element
        self.amount = amount
        self.targets = targets
    }
}

public struct DiagnosticFinding: Codable, Sendable, Equatable {
    public var id: String
    public var severity: Severity
    public var rule: String
    public var message: String
    public var agentFeedback: String
    public var suggestedFix: String
    public var slideIndex: Int
    public var horizontal: Int
    public var vertical: Int
    public var slideID: String?
    public var slideTitle: String?
    public var state: Int
    public var animationTimeMs: Int?
    public var targets: [ReviewIssueTarget]
    public var screenshot: String?

    public init(id: String, severity: Severity, rule: String, message: String, agentFeedback: String,
                suggestedFix: String, slideIndex: Int, horizontal: Int, vertical: Int,
                slideID: String?, slideTitle: String?, state: Int, animationTimeMs: Int?,
                targets: [ReviewIssueTarget], screenshot: String?) {
        self.id = id
        self.severity = severity
        self.rule = rule
        self.message = message
        self.agentFeedback = agentFeedback
        self.suggestedFix = suggestedFix
        self.slideIndex = slideIndex
        self.horizontal = horizontal
        self.vertical = vertical
        self.slideID = slideID
        self.slideTitle = slideTitle
        self.state = state
        self.animationTimeMs = animationTimeMs
        self.targets = targets
        self.screenshot = screenshot
    }
}

public struct DiagnosticsReport: Codable, Sendable, Equatable {
    public var schemaVersion: Int
    public var deck: String
    public var generatedAt: Date
    public var findings: [DiagnosticFinding]

    public init(schemaVersion: Int = 1, deck: String, generatedAt: Date, findings: [DiagnosticFinding]) {
        self.schemaVersion = schemaVersion
        self.deck = deck
        self.generatedAt = generatedAt
        self.findings = findings
    }
}

public struct SlideMetrics: Codable, Sendable, Equatable {
    public var index: Int
    public var horizontal: Int
    public var vertical: Int
    public var state: Int
    public var animationTimeMs: Int?
    public var words: Int
    public var characters: Int
    public var elementCount: Int
    public var occupancy: Double
    public var smallestFontPx: Double
    public var slideID: String?
    public var slideTitle: String?
    public var issues: [ReviewIssue]

    public init(index: Int, horizontal: Int = 0, vertical: Int = 0, state: Int, animationTimeMs: Int? = nil,
                words: Int, characters: Int, elementCount: Int, occupancy: Double, smallestFontPx: Double,
                slideID: String? = nil, slideTitle: String? = nil, issues: [ReviewIssue]) {
        self.index = index
        self.horizontal = horizontal
        self.vertical = vertical
        self.state = state
        self.animationTimeMs = animationTimeMs
        self.words = words
        self.characters = characters
        self.elementCount = elementCount
        self.occupancy = occupancy
        self.smallestFontPx = smallestFontPx
        self.slideID = slideID
        self.slideTitle = slideTitle
        self.issues = issues
    }
}

public struct ReviewReport: Codable, Sendable, Equatable {
    public var deck: String
    public var theme: String?
    public var generatedAt: Date
    public var slides: Int
    public var states: Int
    public var errors: Int
    public var warnings: Int
    public var score: Int
    public var metrics: [SlideMetrics]
    public var artifacts: [String: String]

    public init(deck: String, theme: String?, generatedAt: Date, slides: Int, states: Int, errors: Int,
                warnings: Int, score: Int, metrics: [SlideMetrics], artifacts: [String:String]) {
        self.deck = deck
        self.theme = theme
        self.generatedAt = generatedAt
        self.slides = slides
        self.states = states
        self.errors = errors
        self.warnings = warnings
        self.score = score
        self.metrics = metrics
        self.artifacts = artifacts
    }
}
