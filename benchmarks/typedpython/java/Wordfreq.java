import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Wordfreq {
    static final long VOCAB = 50000;
    static final long MOD = 2147483647L;

    static long nextState(long s) { return (s * 48271) % MOD; }

    static String wordOf(long id) {
        long x = id + 17576;
        StringBuilder sb = new StringBuilder();
        while (x > 0) {
            sb.append((char) (97 + x % 26));
            x /= 26;
        }
        return sb.reverse().toString();
    }

    public static void main(String[] args) {
        int n = Integer.parseInt(args[0]);
        Map<String, Integer> counts = new HashMap<>();
        long s = 12345;
        for (int i = 0; i < n; i++) {
            s = nextState(s);
            long a = s % VOCAB;
            s = nextState(s);
            long b = s % VOCAB;
            counts.merge(wordOf(a * b / VOCAB), 1, Integer::sum);
        }
        long weighted = 0;
        for (int c : counts.values()) weighted += (long) c * c;
        List<String> taken = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < 5; k++) {
            String bestWord = "";
            int bestCount = -1;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                String w = e.getKey();
                int c = e.getValue();
                if (taken.contains(w)) continue;
                if (c > bestCount || (c == bestCount && w.compareTo(bestWord) < 0)) { bestWord = w; bestCount = c; }
            }
            taken.add(bestWord);
            parts.add(bestWord + ":" + bestCount);
        }
        System.out.println(counts.size() + " " + weighted + " " + String.join(",", parts));
    }
}
