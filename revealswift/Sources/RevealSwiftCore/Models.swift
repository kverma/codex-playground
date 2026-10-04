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
    public var css: [String]
    public var allowedComponents: [String]
    public var width: Int
    public var height: Int
    public var rules: ThemeRules

    public init(name: String, displayName: String, version: String = "1.0.0", css: [String] = ["theme.css"],
                allowedComponents: [String] = [], width: Int = 1920, height: Int = 1080, rules: ThemeRules = .init()) {
        self.name = name
        self.displayName = displayName
        self.version = version
        self.css = css
        self.allowedComponents = allowedComponents
        self.width = width
        self.height = height
        self.rules = rules
    }
}

public struct ReviewIssue: Codable, Sendable, Equatable {
    public var severity: Severity
    public var rule: String
    public var message: String
    public var element: String?
    public var amount: Double?

    public init(severity: Severity, rule: String, message: String, element: String? = nil, amount: Double? = nil) {
        self.severity = severity
        self.rule = rule
        self.message = message
        self.element = element
        self.amount = amount
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
    public var issues: [ReviewIssue]

    public init(index: Int, horizontal: Int = 0, vertical: Int = 0, state: Int, animationTimeMs: Int? = nil,
                words: Int, characters: Int, elementCount: Int, occupancy: Double, smallestFontPx: Double,
                issues: [ReviewIssue]) {
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
