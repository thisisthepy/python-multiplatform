public class BinaryTrees {
    static final class Node {
        final Node left, right;
        Node(Node left, Node right) { this.left = left; this.right = right; }
    }

    static Node make(int depth) {
        if (depth > 0) return new Node(make(depth - 1), make(depth - 1));
        return new Node(null, null);
    }

    static int check(Node node) {
        Node left = node.left, right = node.right;
        if (left == null || right == null) return 1;
        return 1 + check(left) + check(right);
    }

    public static void main(String[] args) {
        int n = Integer.parseInt(args[0]);
        int minDepth = 4;
        int maxDepth = Math.max(minDepth + 2, n);
        int stretch = check(make(maxDepth + 1));
        Node longLived = make(maxDepth);
        long total = 0;
        for (int depth = minDepth; depth <= maxDepth; depth += 2) {
            int iterations = 1 << (maxDepth - depth + minDepth);
            long chk = 0;
            for (int i = 0; i < iterations; i++) chk += check(make(depth));
            total += chk;
        }
        System.out.println(stretch + " " + total + " " + check(longLived));
    }
}
