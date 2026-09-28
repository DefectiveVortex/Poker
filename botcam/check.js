// Occlusion report for captured scenes: every card that isn't fully visible from its viewer, and how many
// block-display pixels (chips, dealer button) each viewer can see. Usage: node check.js scenes/<round>/<version> [min%=99]
// Re-renders each scene (the PNGs are rewritten identically) since visibility is measured in the renderer.
const fs = require('fs');
const path = require('path');
const { render } = require('./render');

const dir = process.argv[2];
const min = Number(process.argv[3] || 99) / 100;
const shots = dir.replace(/scenes/, 'shots');
let bad = 0;
for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.json')).sort()) {
  const scene = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
  const { visibility } = render(scene, path.join(shots, f.replace('.json', '.png')));
  const hidden = visibility.filter((v) => v.name.startsWith('card') && v.cover >= 50 && v.visible < min);
  const chips = visibility.filter((v) => v.name.startsWith('block'))
    .map((v) => `${v.name.slice(6).replace('_concrete', '')} ${Math.round(v.cover * v.visible)}`).join(' ') || 'none';
  bad += hidden.length;
  console.log(`${f.replace('.json', '')}: ${hidden.length ? 'HIDDEN ' + hidden.map((v) => `${v.name.slice(5)} ${(100 * v.visible).toFixed(0)}%`).join(', ') : 'all cards visible'}; visible block-display px: ${chips}`);
}
process.exit(bad ? 1 : 0);
