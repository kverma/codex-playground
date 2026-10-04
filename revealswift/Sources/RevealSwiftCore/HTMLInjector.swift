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
        const __rsInit = async () => {
          if (window.Reveal && !Reveal.isReady()) {
            await Reveal.initialize({hash:false, controls:false, progress:false, center:false, transition:'none', backgroundTransition:'none'});
          }
          window.__revealswiftReady = true;
        };
        __rsInit();
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
