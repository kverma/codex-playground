import Foundation

public enum HTMLInjector {
    public static let marker = "<!-- revealswift:runtime -->"
    public static let runtimeBase = "revealswift://runtime"

    public static func inject(html: String, themeCSS: String?, width: Int = 1920, height: Int = 1080) -> String {
        if html.contains(marker) { return html }
        let css = themeCSS.map { "<style id=\"revealswift-theme\">\($0)</style>" } ?? ""
        let head = """
        \(marker)
        <link rel="stylesheet" href="\(runtimeBase)/reveal.css">
        <link rel="stylesheet" href="\(runtimeBase)/plugin/highlight.css">
        \(css)
        """
        let scripts = """
        <script src="\(runtimeBase)/reveal.js"></script>
        <script src="\(runtimeBase)/chart.js"></script>
        <script src="\(runtimeBase)/plugin/markdown.js"></script>
        <script src="\(runtimeBase)/plugin/highlight.js"></script>
        <script src="\(runtimeBase)/plugin/notes.js"></script>
        <script src="\(runtimeBase)/plugin/math.js"></script>
        <script src="\(runtimeBase)/plugin/search.js"></script>
        <script src="\(runtimeBase)/plugin/zoom.js"></script>
        <script>
        window.__revealswiftReady = false;
        window.__revealswiftInitError = null;
        window.__revealswiftDeck = null;

        (async () => {
          try {
            if (!window.Reveal) throw new Error('Reveal.js failed to load');
            const root = document.querySelector('.reveal');
            if (!root) throw new Error('Missing .reveal root');
            const slides = root.querySelector('.slides');
            if (!slides) throw new Error('Missing .reveal > .slides container');

            const builtInPlugins = [
              window.RevealMarkdown,
              window.RevealHighlight,
              window.RevealNotes,
              window.RevealMath?.KaTeX?.(),
              window.RevealSearch,
              window.RevealZoom
            ].filter(Boolean);
            const customPlugins = Array.isArray(window.RevealSwiftPlugins)
              ? window.RevealSwiftPlugins.filter(Boolean)
              : [];
            const plugins = [...builtInPlugins, ...customPlugins];
            const requestedConfig =
              window.RevealSwiftConfig && typeof window.RevealSwiftConfig === 'object'
                ? window.RevealSwiftConfig
                : {};

            const deck = new Reveal(root, {
              ...requestedConfig,
              width: \(width),
              height: \(height),
              margin: 0,
              hash: false,
              controls: false,
              progress: false,
              center: false,
              transition: 'none',
              backgroundTransition: 'none',
              plugins
            });
            window.__revealswiftDeck = deck;
            await deck.initialize();
            deck.sync();

            window.__revealswiftRuntime = {
              offline: true,
              revealVersion: Reveal.VERSION || "\(EmbeddedRuntime.revealVersion)",
              chartVersion: window.Chart ? Chart.version : null,
              katexVersion: window.katex ? window.katex.version : null,
              mathPluginAvailable: Boolean(window.RevealMath),
              customPluginCount: customPlugins.length,
              domSlides: slides.querySelectorAll('section').length,
              revealSlides: deck.getTotalSlides()
            };

            if (window.__revealswiftRuntime.domSlides > 0 && window.__revealswiftRuntime.revealSlides === 0) {
              await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
              deck.sync();
              window.__revealswiftRuntime.revealSlides = deck.getTotalSlides();
            }

            window.__revealswiftReady = true;
          } catch (error) {
            window.__revealswiftInitError = String(error && error.stack ? error.stack : error);
          }
        })();
        </script>
        """
        var out = html
        if let range = out.range(of: "</head>", options: .caseInsensitive) {
            out.insert(contentsOf: head + "\n", at: range.lowerBound)
        } else {
            out = "<head>\(head)</head>" + out
        }
        if let range = out.range(of: "</body>", options: [.caseInsensitive, .backwards]) {
            out.insert(contentsOf: scripts + "\n", at: range.lowerBound)
        } else {
            out += scripts
        }
        return out
    }
}
