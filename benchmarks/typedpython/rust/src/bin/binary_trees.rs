struct Node {
    left: Option<Box<Node>>,
    right: Option<Box<Node>>,
}

fn make(depth: u32) -> Box<Node> {
    if depth > 0 {
        Box::new(Node { left: Some(make(depth - 1)), right: Some(make(depth - 1)) })
    } else {
        Box::new(Node { left: None, right: None })
    }
}

fn check(node: &Node) -> i64 {
    match (&node.left, &node.right) {
        (Some(l), Some(r)) => 1 + check(l) + check(r),
        _ => 1,
    }
}

fn main() {
    let n: u32 = std::env::args().nth(1).unwrap().parse().unwrap();
    let min_depth = 4u32;
    let max_depth = std::cmp::max(min_depth + 2, n);
    let stretch = check(&make(max_depth + 1));
    let long_lived = make(max_depth);
    let mut total = 0i64;
    let mut depth = min_depth;
    while depth <= max_depth {
        let iterations = 1u64 << (max_depth - depth + min_depth);
        let mut chk = 0i64;
        for _ in 0..iterations {
            chk += check(&make(depth));
        }
        total += chk;
        depth += 2;
    }
    println!("{} {} {}", stretch, total, check(&long_lived));
}
