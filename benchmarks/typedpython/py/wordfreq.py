import sys

VOCAB: int = 50000
MOD: int = 2147483647


def next_state(s: int) -> int:
    return (s * 48271) % MOD


def word_of(word_id: int) -> str:
    x: int = word_id + 17576
    w: str = ""
    while x > 0:
        w = chr(97 + x % 26) + w
        x //= 26
    return w


def main() -> None:
    n: int = int(sys.argv[1])
    counts: dict[str, int] = {}
    s: int = 12345
    for _ in range(n):
        s = next_state(s)
        a: int = s % VOCAB
        s = next_state(s)
        b: int = s % VOCAB
        word: str = word_of(a * b // VOCAB)
        counts[word] = counts.get(word, 0) + 1
    weighted: int = 0
    for w, c in counts.items():
        weighted += c * c
    taken: list[str] = []
    parts: list[str] = []
    for _ in range(5):
        best_word: str = ""
        best_count: int = -1
        for w, c in counts.items():
            if w in taken:
                continue
            if c > best_count or (c == best_count and w < best_word):
                best_word = w
                best_count = c
        taken.append(best_word)
        parts.append(f"{best_word}:{best_count}")
    print(f"{len(counts)} {weighted} {','.join(parts)}")


if __name__ == "__main__":
    main()
