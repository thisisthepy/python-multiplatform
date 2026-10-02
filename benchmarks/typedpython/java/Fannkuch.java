public class Fannkuch {
    public static void main(String[] args) {
        int n = Integer.parseInt(args[0]);
        int[] perm1 = new int[n];
        for (int i = 0; i < n; i++) perm1[i] = i;
        int[] count = new int[n];
        int maxFlips = 0, checksum = 0, permCount = 0, r = n;
        while (true) {
            while (r != 1) { count[r - 1] = r; r--; }
            int[] perm = perm1.clone();
            int flips = 0;
            int k = perm[0];
            while (k != 0) {
                int lo = 0, hi = k;
                while (lo < hi) {
                    int t = perm[lo]; perm[lo] = perm[hi]; perm[hi] = t;
                    lo++; hi--;
                }
                flips++;
                k = perm[0];
            }
            if (flips > maxFlips) maxFlips = flips;
            if (permCount % 2 == 0) checksum += flips; else checksum -= flips;
            boolean done = false;
            while (true) {
                if (r == n) { done = true; break; }
                int p0 = perm1[0];
                for (int i = 0; i < r; i++) perm1[i] = perm1[i + 1];
                perm1[r] = p0;
                count[r]--;
                if (count[r] > 0) break;
                r++;
            }
            if (done) break;
            permCount++;
        }
        System.out.println(checksum + " " + maxFlips);
    }
}
