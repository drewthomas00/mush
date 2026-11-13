// The Mush brain: brainSVG(health) returns an SVG string; colorAt(health) its color. Shared by index.html and overlay.html.
const lerp = (a, b, t) => a + (b - a) * t;
const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const toHex = (c) => '#' + c.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('');

// Brain color by health: pink and alive down to swamp.
const STOPS = [[0, '#7d7b52'], [25, '#a3a066'], [45, '#c9ab86'], [70, '#f2a0b3'], [100, '#ff93b6']];
function colorAt(h) {
  for (let i = 1; i < STOPS.length; i++) {
    const [h1, c1] = STOPS[i - 1], [h2, c2] = STOPS[i];
    if (h <= h2) {
      const t = (h - h1) / (h2 - h1);
      const a = hex(c1), b = hex(c2);
      return toHex(a.map((v, k) => lerp(v, b[k], t)));
    }
  }
  return STOPS.at(-1)[1];
}
const shade = (c, f) => toHex(hex(c).map((v) => v * f));

const LOBES = [[72, 92, 40], [112, 66, 44], [158, 74, 40], [186, 108, 32], [56, 128, 32], [120, 124, 50], [172, 138, 32], [90, 150, 30], [150, 152, 30]];

function brainSVG(h) {
  const t = h / 100;
  const fill = colorAt(h), edge = shade(fill, 0.62), fold = shade(fill, 0.78);
  const sag = lerp(0.8, 1, Math.min(1, t * 1.6)); // flattens as it rots
  const widen = lerp(1.12, 1, Math.min(1, t * 1.6));
  const lobes = (attrs) => LOBES.map(([x, y, r]) => `<circle cx="${x}" cy="${y}" r="${r}" ${attrs}/>`).join('');

  // folds
  const folds = `
    <path d="M118 30 C 108 50, 128 62, 116 82 S 126 108, 118 124" />
    <path d="M70 70 C 82 78, 76 92, 90 96" /><path d="M150 46 C 160 58, 150 70, 166 76" />
    <path d="M182 92 C 172 100, 186 110, 176 120" /><path d="M48 116 C 60 112, 64 124, 76 120" />
    <path d="M160 160 C 168 150, 182 156, 186 146" /><path d="M72 160 C 80 150, 92 158, 100 150" />`;

  // eyes
  let eyes;
  const eye = (x, y) => `<circle cx="${x}" cy="${y}" r="11" fill="#fff"/><circle cx="${x + 1}" cy="${y + 2}" r="6" fill="#231d2b"/><circle cx="${x + 3}" cy="${y - 1}" r="2" fill="#fff"/>`;
  if (h <= 0) {
    eyes = [96, 144].map((x) => `<path d="M${x - 8} 112 l16 16 M${x + 8} 112 l-16 16" stroke="#231d2b" stroke-width="4.5" stroke-linecap="round"/>`).join('');
  } else if (h < 25) {
    eyes = [96, 144].map((x) => `<circle cx="${x}" cy="120" r="11" fill="#fff"/><path d="M${x} 120 m0 -1 a2 2 0 1 1 -2 2 a4 4 0 1 1 5 -5 a7 7 0 1 1 -9 9" fill="none" stroke="#231d2b" stroke-width="2.2" stroke-linecap="round"/>`).join('');
  } else {
    // eyelids drop as health falls below 75
    const lid = Math.max(0, Math.min(1, (75 - h) / 50)); // 0 open .. 1 mostly shut
    const drop = lid * 15; // how far the lid comes down over an eye of radius 11
    eyes = [[96, 120], [144, h < 45 ? 123 : 120]].map(([x, y]) => {
      if (lid <= 0) return eye(x, y);
      const edgeY = y - 11 + drop, half = Math.sqrt(Math.max(0, 121 - (edgeY - y) ** 2));
      const id = `lid${x}-${Math.round(h * 10)}`;
      return eye(x, y) + `<clipPath id="${id}"><circle cx="${x}" cy="${y}" r="11.5"/></clipPath>`
        + `<rect clip-path="url(#${id})" x="${x - 12}" y="${y - 12}" width="24" height="${(drop + 1).toFixed(1)}" fill="${fill}"/>`
        + `<path d="M${(x - half).toFixed(1)} ${edgeY.toFixed(1)} L${(x + half).toFixed(1)} ${edgeY.toFixed(1)}" stroke="${edge}" stroke-width="2.6" stroke-linecap="round"/>`;
    }).join('');
  }

  // mouth: smile → flat → frown → tongue
  const curve = lerp(-14, 16, t);
  let mouth = `<path d="M104 148 Q120 ${148 + curve} 136 148" fill="none" stroke="#231d2b" stroke-width="4" stroke-linecap="round"/>`;
  if (h < 25) mouth = `<path d="M102 150 q6 -6 12 0 t12 0 t12 0" fill="none" stroke="#231d2b" stroke-width="4" stroke-linecap="round"/><path d="M122 151 q0 14 7 14 q7 0 6 -12" fill="#e8607a" stroke="#231d2b" stroke-width="2.5"/>`;

  let extras = '';
  if (h >= 85) {
    extras += [[30, 46, 0], [214, 40, .7], [210, 160, 1.3], [24, 150, 1.9]].map(([x, y, d]) =>
      `<path class="spark" style="animation-delay:${d}s" d="M${x} ${y - 9} L${x + 2.5} ${y - 2.5} L${x + 9} ${y} L${x + 2.5} ${y + 2.5} L${x} ${y + 9} L${x - 2.5} ${y + 2.5} L${x - 9} ${y} L${x - 2.5} ${y - 2.5}Z" fill="#ffd166"/>`).join('');
  }
  if (h < 50) {
    const n = h < 25 ? 3 : h < 40 ? 2 : 1;
    extras += [[92, 176, 0, 2.2], [146, 178, .9, 2.8], [120, 182, 1.6, 2.4]].slice(0, n).map(([x, y, d, dur]) =>
      `<path class="drip" style="animation-delay:${d}s;--d:${dur}s" d="M${x} ${y} q-5 9 0 12 q5 -3 0 -12z" fill="${fill}" stroke="${edge}" stroke-width="1.6"/>`).join('');
  }
  if (h < 30) {
    extras += [[0, 4], [1.7, 5.5]].map(([d, dur]) =>
      `<g class="fly" style="animation-delay:-${d}s;--d:${dur}s"><g transform="translate(120 100) rotate(${d ? 200 : 25})"><circle cx="0" cy="${d ? -100 : -94}" r="3.4" fill="#231d2b"/><ellipse cx="-4" cy="${d ? -105 : -99}" rx="4" ry="2.6" fill="#cfd8e6" opacity=".9"/><ellipse cx="4" cy="${d ? -105 : -99}" rx="4" ry="2.6" fill="#cfd8e6" opacity=".9"/></g></g>`).join('');
  }
  const puddle = h < 25 ? `<ellipse cx="120" cy="196" rx="${lerp(96, 70, h / 25)}" ry="12" fill="${fill}" stroke="${edge}" stroke-width="3"/>` : `<ellipse cx="120" cy="200" rx="70" ry="7" fill="#000" opacity=".12"/>`;
  const bob = h >= 65 ? '2.6s' : h >= 25 ? '4s' : '6.5s';

  return `<svg viewBox="0 0 240 215" role="img">
    ${puddle}
    <g class="bob" style="--bob:${bob}">
      <g transform="translate(120 190) scale(${widen} ${sag}) translate(-120 -190)">
        <g fill="${edge}">${lobes(`stroke="${edge}" stroke-width="7"`)}</g>
        <g fill="${fill}">${lobes('')}</g>
        <g fill="none" stroke="${fold}" stroke-width="4" stroke-linecap="round">${folds}</g>
        ${eyes}${mouth}
      </g>
    </g>
    ${extras}
  </svg>`;
}

