class Node {
  constructor(left, right) { this.left = left; this.right = right; }
}
function make(depth) {
  if (depth > 0) return new Node(make(depth - 1), make(depth - 1));
  return new Node(null, null);
}
function check(node) {
  const left = node.left, right = node.right;
  if (left === null || right === null) return 1;
  return 1 + check(left) + check(right);
}
const n = parseInt(process.argv[2], 10);
const minDepth = 4;
const maxDepth = Math.max(minDepth + 2, n);
const stretch = check(make(maxDepth + 1));
const longLived = make(maxDepth);
let total = 0;
for (let depth = minDepth; depth <= maxDepth; depth += 2) {
  const iterations = 1 << (maxDepth - depth + minDepth);
  let chk = 0;
  for (let i = 0; i < iterations; i++) chk += check(make(depth));
  total += chk;
}
console.log(`${stretch} ${total} ${check(longLived)}`);
