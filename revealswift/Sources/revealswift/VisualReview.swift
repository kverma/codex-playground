import Foundation
import RevealSwiftCore

enum VisualReviewSupport {
    static let reviewerRole = "independent-visual-reviewer"

    static let criteria = [
        "visual hierarchy and whether the intended focal point is immediately clear",
        "readability at presentation distance, including type size, contrast, and line length",
        "composition, balance, alignment, spacing, and intentional whitespace",
        "consistency with the selected theme and with adjacent slides",
        "chart and diagram legibility, labeling, scale, and visual usefulness",
        "image quality, cropping, relevance, and whether imagery supports the message",
        "narrative clarity: whether the slide communicates one understandable idea",
        "fragment and animation states: whether progressive disclosure remains coherent",
        "accessibility risks visible in the rendering, especially contrast or tiny text",
        "overall polish: anything technically valid but visually awkward, confusing, or unfinished"
    ]

    static let instructions = [
        "Act as an independent visual reviewer, not as the author. Do not edit the deck during this review pass.",
        "Inspect the contact sheet first for cross-slide consistency and narrative rhythm, then inspect every queued slide/state image.",
        "Use diagnostics.json as deterministic evidence. Do not blindly duplicate deterministic findings; mention them when they materially affect visual quality.",
        "Base each visual finding on something visible in a listed review image. Reference the exact reviewItemID and slideID when available.",
        "Describe what is visually wrong, cite concrete visible evidence, and give a specific correction the authoring agent can make in Reveal HTML/CSS.",
        "Use severity error for presentation-breaking problems, warning for meaningful quality problems, and info only for optional polish.",
        "Set decision to pass only when there are no error or warning findings. Otherwise use needs_changes.",
        "Write only the structured visual-review.json required by outputContract; do not replace it with free-form prose."
    ]

    static let categories = [
        "visual_hierarchy",
        "readability",
        "composition",
        "alignment",
        "whitespace",
        "typography",
        "chart",
        "diagram",
        "imagery",
        "consistency",
        "narrative",
        "accessibility",
        "branding",
        "animation",
        "other"
    ]

    static func makeManifest(
        deck: String,
        generatedAt: Date,
        contactSheet: String?,
        canonical: [(metric: SlideMetrics, image: String)],
        diagnostics: [DiagnosticFinding]
    ) -> VisualReviewManifest {
        var items: [VisualReviewItem] = []

        if let contactSheet {
            items.append(
                VisualReviewItem(
                    id: "visual-overview",
                    kind: "contact-sheet",
                    image: contactSheet,
                    reviewFocus: [
                        "cross-slide consistency",
                        "narrative rhythm",
                        "visual variety without style drift",
                        "relative density and whitespace across the deck"
                    ]
                )
            )
        }

        for pair in canonical.sorted(by: { $0.metric.index < $1.metric.index }) {
            let metric = pair.metric
            items.append(
                VisualReviewItem(
                    id: String(format: "visual-s%03d-canonical", metric.index + 1),
                    kind: "slide-state",
                    image: pair.image,
                    slideIndex: metric.index + 1,
                    horizontal: metric.horizontal,
                    vertical: metric.vertical,
                    slideID: metric.slideID,
                    slideTitle: metric.slideTitle,
                    state: metric.state,
                    animationTimeMs: metric.animationTimeMs,
                    diagnosticIDs: diagnostics
                        .filter { $0.slideIndex == metric.index + 1 }
                        .map(\.id),
                    reviewFocus: [
                        "hierarchy",
                        "readability",
                        "composition and alignment",
                        "whitespace",
                        "theme consistency",
                        "meaning and clarity"
                    ]
                )
            )
        }

        for finding in diagnostics {
            guard let image = finding.screenshot else { continue }
            items.append(
                VisualReviewItem(
                    id: "visual-" + finding.id,
                    kind: "diagnostic-state",
                    image: image,
                    slideIndex: finding.slideIndex,
                    horizontal: finding.horizontal,
                    vertical: finding.vertical,
                    slideID: finding.slideID,
                    slideTitle: finding.slideTitle,
                    state: finding.state,
                    animationTimeMs: finding.animationTimeMs,
                    diagnosticIDs: [finding.id],
                    reviewFocus: [
                        "visual impact of deterministic diagnostic " + finding.id,
                        "whether the deterministic problem causes additional aesthetic or communication issues"
                    ]
                )
            )
        }

        return VisualReviewManifest(
            deck: deck,
            generatedAt: generatedAt,
            reviewerRole: reviewerRole,
            instructions: instructions,
            criteria: criteria,
            deterministicDiagnostics: "diagnostics.json",
            items: items,
            outputContract: VisualReviewOutputContract(
                requiredTopLevelFields: [
                    "schemaVersion",
                    "deck",
                    "reviewerRole",
                    "decision",
                    "summary",
                    "findings"
                ],
                allowedDecisions: ["pass", "needs_changes"],
                allowedSeverities: Severity.allCases.map(\.rawValue),
                allowedCategories: categories
            )
        )
    }

    static func prompt(for manifest: VisualReviewManifest) -> String {
        let itemLines = manifest.items.map { item in
            var context = "- [\(item.id)] \(item.image)"
            if let slideID = item.slideID { context += " slide=#\(slideID)" }
            else if let slideIndex = item.slideIndex { context += " slide=\(slideIndex)" }
            if let state = item.state { context += " state=\(state)" }
            if let animation = item.animationTimeMs { context += " animation=\(animation)ms" }
            if !item.diagnosticIDs.isEmpty {
                context += " diagnostics=" + item.diagnosticIDs.joined(separator: ",")
            }
            return context
        }.joined(separator: "\n")

        return """
        # RevealSwift Visual Review

        You are the independent visual reviewer for `\(manifest.deck)`.

        ## Instructions
        \(manifest.instructions.map { "- " + $0 }.joined(separator: "\n"))

        ## Review criteria
        \(manifest.criteria.map { "- " + $0 }.joined(separator: "\n"))

        ## Inputs
        Read `\(manifest.deterministicDiagnostics)` for deterministic QA context, then inspect these images:

        \(itemLines)

        ## Required output
        Write `\(manifest.outputContract.outputFile)` as JSON with schemaVersion \(manifest.outputContract.schemaVersion).

        Top-level fields:
        - schemaVersion
        - deck
        - reviewerRole = "\(manifest.reviewerRole)"
        - decision = "pass" or "needs_changes"
        - summary
        - findings

        Each finding must contain:
        - id: stable reviewer finding id, preferably `visual-finding-001`, `visual-finding-002`, ...
        - severity: info, warning, or error
        - category: one of \(manifest.outputContract.allowedCategories.joined(separator: ", "))
        - reviewItemID: one of the IDs listed above
        - slideID and/or slideIndex when available
        - description: concise statement of the visual problem
        - evidence: specific visible evidence from the referenced image
        - suggestedFix: concrete Reveal HTML/CSS/layout correction

        Do not edit the deck in this reviewer pass. The authoring agent will consume your structured findings separately.
        """
    }

    static func validate(result: VisualReviewResult, manifest: VisualReviewManifest) -> [String] {
        var errors: [String] = []

        if result.schemaVersion != manifest.outputContract.schemaVersion {
            errors.append("schemaVersion must be \(manifest.outputContract.schemaVersion)")
        }
        if result.deck != manifest.deck {
            errors.append("deck must match manifest deck '\(manifest.deck)'")
        }
        if result.reviewerRole != manifest.reviewerRole {
            errors.append("reviewerRole must be '\(manifest.reviewerRole)'")
        }
        if !manifest.outputContract.allowedDecisions.contains(result.decision) {
            errors.append("decision must be one of: " + manifest.outputContract.allowedDecisions.joined(separator: ", "))
        }
        if result.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            errors.append("summary must not be empty")
        }

        let validItems = Dictionary(uniqueKeysWithValues: manifest.items.map { ($0.id, $0) })
        var findingIDs = Set<String>()

        for finding in result.findings {
            if finding.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                errors.append("finding id must not be empty")
            } else if !findingIDs.insert(finding.id).inserted {
                errors.append("duplicate finding id '\(finding.id)'")
            }
            if !manifest.outputContract.allowedSeverities.contains(finding.severity.rawValue) {
                errors.append("finding \(finding.id) has unsupported severity '\(finding.severity.rawValue)'")
            }
            if !manifest.outputContract.allowedCategories.contains(finding.category) {
                errors.append("finding \(finding.id) has unsupported category '\(finding.category)'")
            }
            guard let item = validItems[finding.reviewItemID] else {
                errors.append("finding \(finding.id) references unknown reviewItemID '\(finding.reviewItemID)'")
                continue
            }
            if let slideID = finding.slideID, let expected = item.slideID, slideID != expected {
                errors.append("finding \(finding.id) slideID '\(slideID)' does not match review item slideID '\(expected)'")
            }
            if let slideIndex = finding.slideIndex, let expected = item.slideIndex, slideIndex != expected {
                errors.append("finding \(finding.id) slideIndex \(slideIndex) does not match review item slideIndex \(expected)")
            }
            if finding.description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                errors.append("finding \(finding.id) description must not be empty")
            }
            if finding.evidence.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                errors.append("finding \(finding.id) evidence must not be empty")
            }
            if finding.suggestedFix.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                errors.append("finding \(finding.id) suggestedFix must not be empty")
            }
        }

        let blocking = result.findings.contains { $0.severity == .warning || $0.severity == .error }
        if result.decision == "pass" && blocking {
            errors.append("decision cannot be pass while warning/error findings exist")
        }
        if result.decision == "needs_changes" && result.findings.isEmpty {
            errors.append("needs_changes requires at least one finding")
        }

        return errors
    }
}
