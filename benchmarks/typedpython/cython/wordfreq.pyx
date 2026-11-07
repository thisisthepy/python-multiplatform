# cython: language_level=3, boundscheck=False, wraparound=False, cdivision=True, initializedcheck=False
# Same algorithm as py/wordfreq.py: the counts stay a Python dict[str, int] (the algorithm's data
# structure), only the arithmetic is typed and words are built in a C buffer. Semantics that differ
# from CPython: C long arithmetic (wraps; the LCG product stays below 2**47 and the weighted sum
# below 2**63 at benchmark size), cdivision.
import sys

cdef long VOCAB = 50000
cdef long MOD = 2147483647


cdef inline long next_state(long s) noexcept nogil:
    return (s * 48271) % MOD


cdef str word_of(long word_id):
    cdef char buf[32]
    cdef int pos = 32
    cdef long x = word_id + 17576
    while x > 0:
        pos -= 1
        buf[pos] = <char>(97 + x % 26)
        x //= 26
    return (<bytes>buf[pos:32]).decode("ascii")


def main():
    cdef long n = int(sys.argv[1])
    cdef dict counts = {}
    cdef long s = 12345, a, b, i, c, weighted = 0, best_count
    cdef str word, w, best_word
    cdef list taken = []
    cdef list parts = []
    for i in range(n):
        s = next_state(s)
        a = s % VOCAB
        s = next_state(s)
        b = s % VOCAB
        word = word_of(a * b // VOCAB)
        counts[word] = <long>counts.get(word, 0) + 1
    for w, c in counts.items():
        weighted += c * c
    for i in range(5):
        best_word = ""
        best_count = -1
        for w, c in counts.items():
            if w in taken:
                continue
            if c > best_count or (c == best_count and w < best_word):
                best_word = w
                best_count = c
        taken.append(best_word)
        parts.append(f"{best_word}:{best_count}")
    print(f"{len(counts)} {weighted} {','.join(parts)}")
