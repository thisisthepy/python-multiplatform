fn fannkuch(n: usize) -> (i64, i64) {
    let mut perm1: Vec<usize> = (0..n).collect();
    let mut count = vec![0usize; n];
    let (mut max_flips, mut checksum, mut perm_count) = (0i64, 0i64, 0i64);
    let mut r = n;
    loop {
        while r != 1 {
            count[r - 1] = r;
            r -= 1;
        }
        let mut perm = perm1.clone();
        let mut flips = 0i64;
        let mut k = perm[0];
        while k != 0 {
            let (mut lo, mut hi) = (0usize, k);
            while lo < hi {
                perm.swap(lo, hi);
                lo += 1;
                hi -= 1;
            }
            flips += 1;
            k = perm[0];
        }
        if flips > max_flips {
            max_flips = flips;
        }
        if perm_count % 2 == 0 {
            checksum += flips;
        } else {
            checksum -= flips;
        }
        loop {
            if r == n {
                return (checksum, max_flips);
            }
            let p0 = perm1[0];
            for i in 0..r {
                perm1[i] = perm1[i + 1];
            }
            perm1[r] = p0;
            count[r] -= 1;
            if count[r] > 0 {
                break;
            }
            r += 1;
        }
        perm_count += 1;
    }
}

fn main() {
    let n: usize = std::env::args().nth(1).unwrap().parse().unwrap();
    let (checksum, max_flips) = fannkuch(n);
    println!("{} {}", checksum, max_flips);
}
