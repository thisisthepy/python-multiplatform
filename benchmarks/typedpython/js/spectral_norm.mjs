function evalA(i, j) {
  return 1.0 / (Math.floor((i + j) * (i + j + 1) / 2) + i + 1);
}
function mulAv(n, v, out) {
  for (let i = 0; i < n; i++) {
    let s = 0.0;
    for (let j = 0; j < n; j++) s += evalA(i, j) * v[j];
    out[i] = s;
  }
}
function mulAtv(n, v, out) {
  for (let i = 0; i < n; i++) {
    let s = 0.0;
    for (let j = 0; j < n; j++) s += evalA(j, i) * v[j];
    out[i] = s;
  }
}
function mulAtAv(n, v, out, tmp) {
  mulAv(n, v, tmp);
  mulAtv(n, tmp, out);
}
const n = parseInt(process.argv[2], 10);
const u = new Array(n).fill(1.0);
const v = new Array(n).fill(0.0);
const tmp = new Array(n).fill(0.0);
for (let k = 0; k < 10; k++) {
  mulAtAv(n, u, v, tmp);
  mulAtAv(n, v, u, tmp);
}
let vbv = 0.0, vv = 0.0;
for (let i = 0; i < n; i++) {
  vbv += u[i] * v[i];
  vv += v[i] * v[i];
}
console.log(Math.sqrt(vbv / vv).toFixed(9));
