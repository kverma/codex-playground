import Foundation

public enum HTMLInjector {
    public static let marker = "<!-- revealswift:runtime -->"

    public static func inject(html: String, themeCSS: String?, runtimeBase: String = "https://cdn.jsdelivr.net/npm/reveal.js@6.0.2") -> String {
        if html.contains(marker) { return html }
        let css = themeCSS.map { "<style id=\"revealswift-theme\">\($0)</style>" } ?? ""
        let head = """
        \(marker)
        <link rel="stylesheet" href="\(runtimeBase)/dist/reveal.css">
        \(css)
        """
        let scripts = """
        <script src="\(runtimeBase)/dist/reveal.js"></script>
        <script src="https://cdn.jsdelivr.net/npm/chart.js@4.5.1/dist/chart.umd.min.js"></script>
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

            const deck = new Reveal(root, {
              hash: false,
              controls: false,
              progress: false,
              center: false,
              transition: 'none',
              backgroundTransition: 'none'
            });
            window.__revealswiftDeck = deck;
            await deck.initialize();
            deck.sync();

            window.__revealswiftRuntime = {
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
        if let range = out.range(of: "</head>", options: .caseInsensitive) { out.insert(contentsOf: head + "\n", at: range.lowerBound) }
        else { out = "<head>\(head)</head>" + out }
        if let range = out.range(of: "</body>", options: .caseInsensitive) { out.insert(contentsOf: scripts + "\n", at: range.lowerBound) }
        else { out += scripts }
        return out
    }
}
