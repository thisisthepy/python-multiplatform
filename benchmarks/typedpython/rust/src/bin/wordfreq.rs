use std::collections::HashMap;

const VOCAB: u64 = 50000;
const MOD: u64 = 2147483647;

fn next_state(s: u64) -> u64 {
    (s * 48271) % MOD
}

fn word_of(id: u64) -> String {
    let mut x = id + 17576;
    let mut bytes: Vec<u8> = Vec::new();
    while x > 0 {
        bytes.push(97 + (x % 26) as u8);
        x /= 26;
    }
    bytes.reverse();
    String::from_utf8(bytes).unwrap()
}

fn main() {
    let n: u64 = std::env::args().nth(1).unwrap().parse().unwrap();
    let mut counts: HashMap<String, i64> = HashMap::new();
    let mut s: u64 = 12345;
    for _ in 0..n {
        s = next_state(s);
        let a = s % VOCAB;
        s = next_state(s);
        let b = s % VOCAB;
        *counts.entry(word_of(a * b / VOCAB)).or_insert(0) += 1;
    }
    let mut weighted = 0i64;
    for c in counts.values() {
        weighted += c * c;
    }
    let mut taken: Vec<String> = Vec::new();
    let mut parts: Vec<String> = Vec::new();
    for _ in 0..5 {
        let mut best_word = String::new();
        let mut best_count = -1i64;
        for (w, &c) in &counts {
            if taken.contains(w) {
                continue;
            }
            if c > best_count || (c == best_count && *w < best_word) {
                best_word = w.clone();
                best_count = c;
            }
        }
        parts.push(format!("{}:{}", best_word, best_count));
        taken.push(best_word);
    }
    println!("{} {} {}", counts.len(), weighted, parts.join(","));
}
