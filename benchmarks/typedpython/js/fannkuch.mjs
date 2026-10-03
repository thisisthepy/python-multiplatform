function fannkuch(n) {
  const perm1 = Array.from({ length: n }, (_, i) => i);
  const count = new Array(n).fill(0);
  let maxFlips = 0, checksum = 0, permCount = 0, r = n;
  for (;;) {
    while (r !== 1) { count[r - 1] = r; r--; }
    const perm = perm1.slice();
    let flips = 0;
    let k = perm[0];
    while (k !== 0) {
      let lo = 0, hi = k;
      while (lo < hi) {
        const t = perm[lo]; perm[lo] = perm[hi]; perm[hi] = t;
        lo++; hi--;
      }
      flips++;
      k = perm[0];
    }
    if (flips > maxFlips) maxFlips = flips;
    if (permCount % 2 === 0) checksum += flips; else checksum -= flips;
    for (;;) {
      if (r === n) return [checksum, maxFlips];
      const p0 = perm1[0];
      for (let i = 0; i < r; i++) perm1[i] = perm1[i + 1];
      perm1[r] = p0;
      count[r]--;
      if (count[r] > 0) break;
      r++;
    }
    permCount++;
  }
}
const [checksum, maxFlips] = fannkuch(parseInt(process.argv[2], 10));
console.log(`${checksum} ${maxFlips}`);
