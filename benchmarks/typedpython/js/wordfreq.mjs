const VOCAB = 50000;
const MOD = 2147483647;
function nextState(s) { return (s * 48271) % MOD; }
function wordOf(id) {
  let x = id + 17576;
  let w = "";
  while (x > 0) {
    w = String.fromCharCode(97 + (x % 26)) + w;
    x = Math.floor(x / 26);
  }
  return w;
}
const n = parseInt(process.argv[2], 10);
const counts = new Map();
let s = 12345;
for (let i = 0; i < n; i++) {
  s = nextState(s);
  const a = s % VOCAB;
  s = nextState(s);
  const b = s % VOCAB;
  const word = wordOf(Math.floor((a * b) / VOCAB));
  counts.set(word, (counts.get(word) ?? 0) + 1);
}
let weighted = 0;
for (const [, c] of counts) weighted += c * c;
const taken = [];
const parts = [];
for (let k = 0; k < 5; k++) {
  let bestWord = "";
  let bestCount = -1;
  for (const [w, c] of counts) {
    if (taken.includes(w)) continue;
    if (c > bestCount || (c === bestCount && w < bestWord)) { bestWord = w; bestCount = c; }
  }
  taken.push(bestWord);
  parts.push(`${bestWord}:${bestCount}`);
}
console.log(`${counts.size} ${weighted} ${parts.join(",")}`);
