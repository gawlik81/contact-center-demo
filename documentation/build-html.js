// Konwerter dokumentacji Markdown -> HTML (statyczne pliki w documentation/tech/html/)
const fs = require('fs');
const path = require('path');
const { marked } = require('marked');

const docsDir = path.join(__dirname, 'tech');
const outDir = path.join(docsDir, 'html');

const files = fs
  .readdirSync(docsDir)
  .filter((f) => f.endsWith('.md'))
  .sort();

const template = (title, body) => `<!DOCTYPE html>
<html lang="pl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${title} – Contact Center SaaS – Dokumentacja</title>
<style>
  :root { --max-width: 980px; --fg: #1a1a1a; --bg: #fff; --accent: #2563eb; --code-bg: #f4f4f5; --border: #e5e7eb; }
  body { font-family: -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; color: var(--fg); background: var(--bg); margin: 0; }
  .layout { display: flex; max-width: var(--max-width); margin: 0 auto; }
  main { padding: 2rem; max-width: 100%; overflow-x: auto; flex: 1; }
  h1, h2, h3, h4 { line-height: 1.25; }
  h1 { border-bottom: 2px solid var(--border); padding-bottom: .3em; }
  h2 { border-bottom: 1px solid var(--border); padding-bottom: .2em; margin-top: 2em; }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }
  code { background: var(--code-bg); padding: .15em .35em; border-radius: 4px; font-size: .9em; }
  pre { background: var(--code-bg); padding: 1em; border-radius: 6px; overflow-x: auto; }
  pre code { background: none; padding: 0; }
  table { border-collapse: collapse; width: 100%; margin: 1em 0; font-size: .92em; }
  th, td { border: 1px solid var(--border); padding: .5em .7em; text-align: left; }
  th { background: var(--code-bg); }
  blockquote { border-left: 4px solid var(--accent); margin: 1em 0; padding: .3em 1em; background: var(--code-bg); }
  .top-nav { font-size: .9em; margin-bottom: 1.5em; }
  .top-nav a { margin-right: .5em; }
  .mermaid { background: var(--bg); }
</style>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
</head>
<body>
<div class="layout">
<main>
<div class="top-nav"><a href="00-index.html">&larr; Spis treści</a></div>
${body}
</main>
</div>
<script>
  if (typeof mermaid !== 'undefined') {
    mermaid.initialize({ startOnLoad: true, theme: 'default' });
  }
</script>
</body>
</html>
`;

if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

for (const file of files) {
  const md = fs.readFileSync(path.join(docsDir, file), 'utf-8');
  // przepisz linki .md -> .html (linki relatywne w dokumentacji)
  const mdRewritten = md
    .replace(/\((\d{2}-[a-z-]+)\.md(#[^)]*)?\)/g, (m, p1, p2) => `(${p1}.html${p2 || ''})`)
    // ../plugin/*.md -> ../../plugin/*.html (wyjście jest teraz w tech/html/, guide jest w documentation/plugin/)
    .replace(/\(\.\.\/plugin\/([a-z0-9-]+)\.md(#[^)]*)?\)/g, (m, p1, p2) => `(../../plugin/${p1}.html${p2 || ''})`)
    // ../plugin/*.html -> ../../plugin/*.html (ten sam powód — przesuń w górę z tech/html/)
    .replace(/\(\.\.\/plugin\/([a-z0-9-]+)\.html(#[^)]*)?\)/g, (m, p1, p2) => `(../../plugin/${p1}.html${p2 || ''})`);
  let body = marked.parse(mdRewritten);
  // umożliwia renderowanie diagramów mermaid w przeglądarce (mermaid.js z CDN)
  body = body.replace(/<pre><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/g, (m, code) => {
    const decoded = code.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'");
    return `<pre class="mermaid">${decoded}</pre>`;
  });
  const titleMatch = md.match(/^#\s+(.+)$/m);
  const title = titleMatch ? titleMatch[1] : file;
  const htmlFile = file.replace(/\.md$/, '.html');
  fs.writeFileSync(path.join(outDir, htmlFile), template(title, body));
  console.log('Wygenerowano', htmlFile);
}
