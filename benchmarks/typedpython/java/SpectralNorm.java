import java.util.Locale;

public class SpectralNorm {
    static double evalA(int i, int j) {
        return 1.0 / ((i + j) * (i + j + 1) / 2 + i + 1);
    }

    static void mulAv(int n, double[] v, double[] out) {
        for (int i = 0; i < n; i++) {
            double s = 0.0;
            for (int j = 0; j < n; j++) s += evalA(i, j) * v[j];
            out[i] = s;
        }
    }

    static void mulAtv(int n, double[] v, double[] out) {
        for (int i = 0; i < n; i++) {
            double s = 0.0;
            for (int j = 0; j < n; j++) s += evalA(j, i) * v[j];
            out[i] = s;
        }
    }

    static void mulAtAv(int n, double[] v, double[] out, double[] tmp) {
        mulAv(n, v, tmp);
        mulAtv(n, tmp, out);
    }

    public static void main(String[] args) {
        int n = Integer.parseInt(args[0]);
        double[] u = new double[n];
        double[] v = new double[n];
        double[] tmp = new double[n];
        java.util.Arrays.fill(u, 1.0);
        for (int k = 0; k < 10; k++) {
            mulAtAv(n, u, v, tmp);
            mulAtAv(n, v, u, tmp);
        }
        double vbv = 0.0, vv = 0.0;
        for (int i = 0; i < n; i++) {
            vbv += u[i] * v[i];
            vv += v[i] * v[i];
        }
        System.out.println(String.format(Locale.ROOT, "%.9f", Math.sqrt(vbv / vv)));
    }
}
